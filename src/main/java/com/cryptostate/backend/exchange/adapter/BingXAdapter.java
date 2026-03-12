package com.cryptostate.backend.exchange.adapter;

import com.cryptostate.backend.transaction.model.NormalizedTransaction;
import com.cryptostate.backend.transaction.model.TransactionType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Adaptador para BingX.
 *
 * Endpoints a implementar (BingX API v3):
 * - GET /openApi/spot/v1/trade/historyOrders   (spot)
 * - GET /openApi/swap/v2/trade/allOrders       (perpetual futures)
 * - GET /openApi/wallets/v1/capital/deposit/hisrec
 *
 * TODO: Implementar firma HMAC-SHA256 requerida por BingX API.
 *
 * NOTA: mientras la integración real no esté lista, fetchAndNormalize()
 * devuelve datos mock para permitir pruebas end-to-end del flujo completo.
 */
@Slf4j
@Component
public class BingXAdapter implements ExchangeAdapter {

    private static final String BASE_URL = "https://open-api.bingx.com";
    private static final String EXCHANGE_ID = "bingx";

    private final WebClient webClient;

    public BingXAdapter(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(BASE_URL).build();
    }

    @Override
    public String getExchangeId() {
        return EXCHANGE_ID;
    }

    @Override
    public List<NormalizedTransaction> fetchAndNormalize(
            String apiKey, String apiSecret, Instant from, Instant to, String userId) {
        log.info("Sincronizando BingX para userId={} [MOCK DATA — API real pendiente]", userId);
        return generateMockTransactions(userId, from, to);
    }

    // ── Mock data ─────────────────────────────────────────────────────────────

    private static final String[][] PAIRS = {
        {"BTC", "USDT"}, {"ETH", "USDT"}, {"SOL", "USDT"},
        {"BNB", "USDT"}, {"XRP", "USDT"}, {"ADA", "USDT"},
    };

    private static final Map<String, double[]> BASE_PRICES = Map.of(
        "BTC", new double[]{58000, 72000},
        "ETH", new double[]{2800, 3800},
        "SOL", new double[]{120, 200},
        "BNB", new double[]{350, 600},
        "XRP", new double[]{0.45, 0.75},
        "ADA", new double[]{0.35, 0.60}
    );

    private List<NormalizedTransaction> generateMockTransactions(String userId, Instant from, Instant to) {
        Random rnd = new Random(userId.hashCode());
        List<NormalizedTransaction> txs = new ArrayList<>();

        Instant start = from != null ? from : Instant.now().minus(90, ChronoUnit.DAYS);
        Instant end   = to   != null ? to   : Instant.now();
        long rangeSeconds = end.getEpochSecond() - start.getEpochSecond();

        int count = 15 + rnd.nextInt(10); // 15-24 transacciones

        for (int i = 0; i < count; i++) {
            String[] pair   = PAIRS[rnd.nextInt(PAIRS.length)];
            String base     = pair[0];
            String quote    = pair[1];
            double[] range  = BASE_PRICES.get(base);
            double price    = range[0] + rnd.nextDouble() * (range[1] - range[0]);
            double qty      = 0.001 + rnd.nextDouble() * (base.equals("BTC") ? 0.1 : 2.0);
            double fee      = qty * price * 0.001;

            // alternar compra/venta con sesgo a compras
            TransactionType type = rnd.nextInt(3) == 0 ? TransactionType.SPOT_SELL : TransactionType.SPOT_BUY;

            BigDecimal priceBD = bd(price, 4);
            BigDecimal qtyBD   = bd(qty, 6);

            // PnL simulado solo en ventas
            BigDecimal pnl = null;
            if (type == TransactionType.SPOT_SELL) {
                double costBasis = price * (0.85 + rnd.nextDouble() * 0.3); // costo ±15%
                pnl = bd((price - costBasis) * qty, 4);
            }

            Instant ts = start.plusSeconds((long)(rnd.nextDouble() * rangeSeconds));

            txs.add(NormalizedTransaction.builder()
                    .id(UUID.randomUUID())
                    .userId(UUID.fromString(userId))
                    .exchangeId(EXCHANGE_ID)
                    .externalId("bingx-mock-" + UUID.randomUUID().toString().substring(0, 8))
                    .type(type)
                    .baseAsset(base)
                    .quoteAsset(quote)
                    .quantity(qtyBD)
                    .price(priceBD)
                    .fee(bd(fee, 6))
                    .feeAsset(quote)
                    .realizedPnl(pnl)
                    .timestamp(ts)
                    .rawData(Map.of("mock", true, "source", "bingx-adapter-dev"))
                    .build());
        }

        txs.sort(Comparator.comparing(NormalizedTransaction::getTimestamp));
        log.info("Mock BingX generó {} transacciones para userId={}", txs.size(), userId);
        return txs;
    }

    private BigDecimal bd(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
