package com.cryptostate.backend.common.util;

import com.cryptostate.backend.common.model.HistoricalPrice;
import com.cryptostate.backend.common.model.HistoricalPriceKey;
import com.cryptostate.backend.common.repository.HistoricalPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servicio de precios históricos en USD.
 *
 * Orden de búsqueda:
 *   1. Cache en memoria (sin I/O)
 *   2. BD: tabla historical_prices (persistente entre reinicios)
 *   3. Binance API (Plan A: sin key, ~3000 req/min, OHLC real)
 *   4. CoinGecko API (Plan B: 30 req/min free, con retry en 429)
 *   5. CoinCap API (Plan C: 200 req/min, promedio diario)
 *
 * Todos los precios obtenidos se persisten en BD para evitar llamadas futuras.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoinGeckoPriceService {

    private static final DateTimeFormatter COINGECKO_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    // Monedas estables — precio = 1.0 USD
    private static final Set<String> STABLECOINS = Set.of(
            "USDT", "USDC", "BUSD", "USD", "DAI", "TUSD", "USDP", "FDUSD", "USDD"
    );

    // Ticker → ID CoinGecko
    private static final Map<String, String> COINGECKO_IDS = Map.ofEntries(
            Map.entry("BTC",     "bitcoin"),
            Map.entry("ETH",     "ethereum"),
            Map.entry("BNB",     "binancecoin"),
            Map.entry("SOL",     "solana"),
            Map.entry("XRP",     "ripple"),
            Map.entry("ADA",     "cardano"),
            Map.entry("DOGE",    "dogecoin"),
            Map.entry("DOT",     "polkadot"),
            Map.entry("NEAR",    "near-protocol"),
            Map.entry("AVAX",    "avalanche-2"),
            Map.entry("LINK",    "chainlink"),
            Map.entry("LTC",     "litecoin"),
            Map.entry("BCH",     "bitcoin-cash"),
            Map.entry("ATOM",    "cosmos"),
            Map.entry("UNI",     "uniswap"),
            Map.entry("PEPE",    "pepe"),
            Map.entry("1000PEPE","pepe"),
            Map.entry("SHIB",    "shiba-inu"),
            Map.entry("1000SHIB","shiba-inu"),
            Map.entry("TRX",     "tron"),
            Map.entry("MATIC",   "matic-network"),
            Map.entry("POL",     "matic-network"),
            Map.entry("OP",      "optimism"),
            Map.entry("ARB",     "arbitrum"),
            Map.entry("SUI",     "sui"),
            Map.entry("TON",     "the-open-network"),
            Map.entry("INJ",     "injective-protocol"),
            Map.entry("FTM",     "fantom"),
            Map.entry("JASMY",   "jasmycoin"),
            Map.entry("SAND",    "the-sandbox"),
            Map.entry("MANA",    "decentraland"),
            Map.entry("APT",     "aptos"),
            Map.entry("FIL",     "filecoin"),
            Map.entry("ETC",     "ethereum-classic"),
            Map.entry("HBAR",    "hedera-hashgraph"),
            Map.entry("ICP",     "internet-computer"),
            Map.entry("VET",     "vechain"),
            Map.entry("STX",     "blockstack"),
            Map.entry("EOS",     "eos"),
            Map.entry("XLM",     "stellar"),
            Map.entry("ALGO",    "algorand"),
            Map.entry("THETA",   "theta-token"),
            Map.entry("AAVE",    "aave"),
            Map.entry("MKR",     "maker"),
            Map.entry("SNX",     "synthetix-network-token"),
            Map.entry("CRV",     "curve-dao-token"),
            Map.entry("LDO",     "lido-dao"),
            Map.entry("WIF",     "dogwifcoin"),
            Map.entry("BONK",    "bonk"),
            Map.entry("FLOKI",   "floki"),
            Map.entry("NOT",     "notcoin"),
            Map.entry("TRUMP",   "maga")
    );

    // Ticker → símbolo Binance (par USDT para el spot market)
    private static final Map<String, String> BINANCE_SYMBOLS = Map.ofEntries(
            Map.entry("BTC",     "BTCUSDT"),
            Map.entry("ETH",     "ETHUSDT"),
            Map.entry("BNB",     "BNBUSDT"),
            Map.entry("SOL",     "SOLUSDT"),
            Map.entry("XRP",     "XRPUSDT"),
            Map.entry("ADA",     "ADAUSDT"),
            Map.entry("DOGE",    "DOGEUSDT"),
            Map.entry("DOT",     "DOTUSDT"),
            Map.entry("NEAR",    "NEARUSDT"),
            Map.entry("AVAX",    "AVAXUSDT"),
            Map.entry("LINK",    "LINKUSDT"),
            Map.entry("LTC",     "LTCUSDT"),
            Map.entry("BCH",     "BCHUSDT"),
            Map.entry("ATOM",    "ATOMUSDT"),
            Map.entry("UNI",     "UNIUSDT"),
            Map.entry("PEPE",    "PEPEUSDT"),
            Map.entry("1000PEPE","1000PEPEUSDT"),
            Map.entry("SHIB",    "SHIBUSDT"),
            Map.entry("1000SHIB","1000SHIBUSDT"),
            Map.entry("TRX",     "TRXUSDT"),
            Map.entry("MATIC",   "MATICUSDT"),
            Map.entry("POL",     "POLUSDT"),
            Map.entry("OP",      "OPUSDT"),
            Map.entry("ARB",     "ARBUSDT"),
            Map.entry("SUI",     "SUIUSDT"),
            Map.entry("TON",     "TONUSDT"),
            Map.entry("INJ",     "INJUSDT"),
            Map.entry("FTM",     "FTMUSDT"),
            Map.entry("JASMY",   "JASMYUSDT"),
            Map.entry("SAND",    "SANDUSDT"),
            Map.entry("MANA",    "MANAUSDT"),
            Map.entry("APT",     "APTUSDT"),
            Map.entry("FIL",     "FILUSDT"),
            Map.entry("ETC",     "ETCUSDT"),
            Map.entry("HBAR",    "HBARUSDT"),
            Map.entry("ICP",     "ICPUSDT"),
            Map.entry("VET",     "VETUSDT"),
            Map.entry("STX",     "STXUSDT"),
            Map.entry("EOS",     "EOSUSDT"),
            Map.entry("XLM",     "XLMUSDT"),
            Map.entry("ALGO",    "ALGOUSDT"),
            Map.entry("THETA",   "THETAUSDT"),
            Map.entry("AAVE",    "AAVEUSDT"),
            Map.entry("MKR",     "MKRUSDT"),
            Map.entry("SNX",     "SNXUSDT"),
            Map.entry("CRV",     "CRVUSDT"),
            Map.entry("LDO",     "LDOUSDT"),
            Map.entry("WIF",     "WIFUSDT"),
            Map.entry("BONK",    "BONKUSDT"),
            Map.entry("FLOKI",   "FLOKIUSDT"),
            Map.entry("NOT",     "NOTUSDT"),
            Map.entry("TRUMP",   "TRUMPUSDT")
    );

    // Ticker → slug CoinCap (asset ID)
    private static final Map<String, String> COINCAP_IDS = Map.ofEntries(
            Map.entry("BTC",     "bitcoin"),
            Map.entry("ETH",     "ethereum"),
            Map.entry("BNB",     "binance-coin"),
            Map.entry("SOL",     "solana"),
            Map.entry("XRP",     "xrp"),
            Map.entry("ADA",     "cardano"),
            Map.entry("DOGE",    "dogecoin"),
            Map.entry("DOT",     "polkadot"),
            Map.entry("NEAR",    "near-protocol"),
            Map.entry("AVAX",    "avalanche"),
            Map.entry("LINK",    "chainlink"),
            Map.entry("LTC",     "litecoin"),
            Map.entry("BCH",     "bitcoin-cash"),
            Map.entry("ATOM",    "cosmos"),
            Map.entry("UNI",     "uniswap"),
            Map.entry("PEPE",    "pepe"),
            Map.entry("SHIB",    "shiba-inu"),
            Map.entry("TRX",     "tron"),
            Map.entry("MATIC",   "polygon"),
            Map.entry("OP",      "optimism"),
            Map.entry("ARB",     "arbitrum"),
            Map.entry("SUI",     "sui"),
            Map.entry("TON",     "toncoin"),
            Map.entry("INJ",     "injective-protocol"),
            Map.entry("FTM",     "fantom"),
            Map.entry("SAND",    "the-sandbox"),
            Map.entry("MANA",    "decentraland"),
            Map.entry("APT",     "aptos"),
            Map.entry("FIL",     "filecoin"),
            Map.entry("ETC",     "ethereum-classic"),
            Map.entry("HBAR",    "hedera-hashgraph"),
            Map.entry("ICP",     "internet-computer"),
            Map.entry("VET",     "vechain"),
            Map.entry("EOS",     "eos"),
            Map.entry("XLM",     "stellar"),
            Map.entry("ALGO",    "algorand"),
            Map.entry("AAVE",    "aave"),
            Map.entry("MKR",     "maker"),
            Map.entry("CRV",     "curve-dao-token"),
            Map.entry("WIF",     "dogwifhat"),
            Map.entry("BONK",    "bonk"),
            Map.entry("FLOKI",   "floki-inu")
    );

    // Multiplicador para contratos con prefijo "1000"
    private static final Map<String, BigDecimal> CONTRACT_MULTIPLIER = Map.of(
            "1000PEPE",  new BigDecimal("0.001"),
            "1000SHIB",  new BigDecimal("0.001"),
            "1000FLOKI", new BigDecimal("0.001")
    );

    // Cache en memoria: "ASSET:YYYY-MM-DD" → Optional<precio> (empty = ya intentado, sin resultado)
    private final Map<String, Optional<BigDecimal>> memoryCache = new ConcurrentHashMap<>();

    private final RestTemplate restTemplate;
    private final HistoricalPriceRepository priceRepository;

    // ── API pública ───────────────────────────────────────────────────────────

    public boolean isStablecoin(String asset) {
        return asset != null && STABLECOINS.contains(asset.toUpperCase());
    }

    /**
     * Obtiene precio USD del asset en la fecha del timestamp.
     * Orden: memoria → BD → Binance → CoinGecko → CoinCap.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BigDecimal getPriceUsd(String asset, Instant timestamp) {
        if (asset == null || asset.isBlank()) return null;

        String assetUpper = asset.toUpperCase();
        if (isStablecoin(assetUpper)) return BigDecimal.ONE;

        LocalDate date = timestamp.atZone(ZoneOffset.UTC).toLocalDate();
        String cacheKey = assetUpper + ":" + date;

        // 1. Cache en memoria
        if (memoryCache.containsKey(cacheKey)) {
            return memoryCache.get(cacheKey).orElse(null);
        }

        // 2. BD
        Optional<HistoricalPrice> dbEntry = priceRepository.findById(new HistoricalPriceKey(assetUpper, date));
        if (dbEntry.isPresent()) {
            BigDecimal price = dbEntry.get().getPriceUsd();
            memoryCache.put(cacheKey, Optional.of(price));
            log.debug("[BD] {} @ {} = {} USD", assetUpper, date, price);
            return price;
        }

        // 3–5. Fuentes externas en cascada
        BigDecimal price = fetchFromSources(assetUpper, date);
        persistAndCache(assetUpper, date, cacheKey, price);
        return price;
    }

    /**
     * Convierte un monto en `asset` a USD usando precio histórico.
     */
    public BigDecimal toUsd(BigDecimal amount, String asset, Instant timestamp) {
        if (amount == null) return null;
        if (asset == null || asset.isBlank()) return null;

        String assetUpper = asset.toUpperCase();
        if (isStablecoin(assetUpper)) return amount;

        BigDecimal price = getPriceUsd(assetUpper, timestamp);
        if (price == null) return null;

        BigDecimal multiplier = CONTRACT_MULTIPLIER.getOrDefault(assetUpper, BigDecimal.ONE);
        return amount.multiply(price).multiply(multiplier);
    }

    // ── Fuentes externas ──────────────────────────────────────────────────────

    private BigDecimal fetchFromSources(String asset, LocalDate date) {
        BigDecimal price;

        // Plan A: Binance (sin key, muy generoso)
        price = fetchFromBinance(asset, date);
        if (price != null) return price;

        // Plan B: CoinGecko (con retry en 429)
        price = fetchFromCoinGecko(asset, date);
        if (price != null) return price;

        // Plan C: CoinCap
        price = fetchFromCoinCap(asset, date);
        return price;
    }

    /** Plan A — Binance klines, precio de cierre diario */
    private BigDecimal fetchFromBinance(String asset, LocalDate date) {
        String symbol = BINANCE_SYMBOLS.get(asset);
        if (symbol == null) return null;

        try {
            long startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long endMs   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1;

            String url = "https://api.binance.com/api/v3/klines"
                    + "?symbol=" + symbol
                    + "&interval=1d"
                    + "&startTime=" + startMs
                    + "&endTime=" + endMs
                    + "&limit=1";

            Object[] response = restTemplate.getForObject(url, Object[].class);
            if (response == null || response.length == 0) return null;

            @SuppressWarnings("unchecked")
            List<Object> candle = (List<Object>) response[0];
            if (candle == null || candle.size() < 5) return null;

            BigDecimal price = new BigDecimal(candle.get(4).toString()); // close price
            log.debug("[Binance] {} @ {} = {} USD", asset, date, price);
            return price;

        } catch (Exception e) {
            log.debug("[Binance] Fallo para {}/{}: {}", asset, date, e.getMessage());
            return null;
        }
    }

    /** Plan B — CoinGecko history, con retry en 429 */
    private BigDecimal fetchFromCoinGecko(String asset, LocalDate date) {
        String coinId = COINGECKO_IDS.get(asset);
        if (coinId == null) return null;

        String dateStr = date.format(COINGECKO_DATE_FMT);
        String url = "https://api.coingecko.com/api/v3/coins/" + coinId
                + "/history?date=" + dateStr + "&localization=false";

        long[] backoffs = {15_000L, 45_000L, 90_000L};

        for (int attempt = 0; attempt <= backoffs.length; attempt++) {
            try {
                Thread.sleep(500); // mínimo entre calls

                @SuppressWarnings("unchecked")
                Map<String, Object> response = restTemplate.getForObject(url, Map.class);
                if (response == null) return null;

                @SuppressWarnings("unchecked")
                Map<String, Object> marketData = (Map<String, Object>) response.get("market_data");
                if (marketData == null) return null;

                @SuppressWarnings("unchecked")
                Map<String, Object> currentPrice = (Map<String, Object>) marketData.get("current_price");
                if (currentPrice == null) return null;

                Object usdPrice = currentPrice.get("usd");
                if (usdPrice == null) return null;

                BigDecimal price = new BigDecimal(usdPrice.toString());
                log.debug("[CoinGecko] {} @ {} = {} USD", asset, date, price);
                return price;

            } catch (HttpClientErrorException e) {
                if (e.getStatusCode().value() == 429 && attempt < backoffs.length) {
                    log.warn("[CoinGecko] 429 para {}/{} — esperando {}s (intento {}/{})",
                            asset, date, backoffs[attempt] / 1000, attempt + 1, backoffs.length);
                    try { Thread.sleep(backoffs[attempt]); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    log.debug("[CoinGecko] Fallo para {}/{}: {}", asset, date, e.getMessage());
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                log.debug("[CoinGecko] Fallo para {}/{}: {}", asset, date, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** Plan C — CoinCap history, promedio diario */
    private BigDecimal fetchFromCoinCap(String asset, LocalDate date) {
        String slug = COINCAP_IDS.get(asset);
        if (slug == null) {
            // Para assets con prefijo 1000X usar la moneda base
            String base = asset.startsWith("1000") ? asset.substring(4) : asset;
            slug = COINCAP_IDS.get(base);
        }
        if (slug == null) {
            log.debug("[CoinCap] Sin mapping para asset={}", asset);
            return null;
        }

        try {
            long startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long endMs   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();

            String url = "https://api.coincap.io/v2/assets/" + slug
                    + "/history?interval=d1&start=" + startMs + "&end=" + endMs;

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            if (response == null) return null;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            if (data == null || data.isEmpty()) return null;

            Object priceUsd = data.get(0).get("priceUsd");
            if (priceUsd == null) return null;

            BigDecimal price = new BigDecimal(priceUsd.toString());
            log.debug("[CoinCap] {} @ {} = {} USD", asset, date, price);
            return price;

        } catch (Exception e) {
            log.warn("[CoinCap] Fallo para {}/{}: {}", asset, date, e.getMessage());
            return null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void persistAndCache(String asset, LocalDate date, String cacheKey, BigDecimal price) {
        if (price != null) {
            try {
                priceRepository.save(HistoricalPrice.of(asset, date, price));
            } catch (Exception e) {
                log.warn("No se pudo persistir precio {}/{} en BD: {}", asset, date, e.getMessage());
            }
            memoryCache.put(cacheKey, Optional.of(price));
        } else {
            log.warn("Sin precio disponible para {}/{} en ninguna fuente", asset, date);
            memoryCache.put(cacheKey, Optional.empty());
        }
    }
}
