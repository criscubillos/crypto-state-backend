package com.cryptostate.backend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.spy.memcached.MemcachedClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetSocketAddress;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MemcachedConfig {

    private final AppProperties appProperties;

    @Bean
    public MemcachedClient memcachedClient() throws IOException {
        AppProperties.Memcached cfg = appProperties.getMemcached();
        log.info("Conectando a Memcached en {}:{}", cfg.getHost(), cfg.getPort());
        return new MemcachedClient(new InetSocketAddress(cfg.getHost(), cfg.getPort()));
    }
}
