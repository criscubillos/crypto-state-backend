package com.cryptostate.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Memcached memcached = new Memcached();
    private Encryption encryption = new Encryption();
    private Cloudflare cloudflare = new Cloudflare();
    private Resend resend = new Resend();
    private Ollama ollama = new Ollama();
    private RateLimit rateLimit = new RateLimit();
    private List<String> allowedOrigins = new java.util.ArrayList<>(List.of("http://localhost:9000"));

    @Getter @Setter
    public static class Jwt {
        private String secret;
        private long accessTokenExpirationMs = 900000L;
        private long refreshTokenExpirationMs = 604800000L;
    }

    @Getter @Setter
    public static class Memcached {
        private String host = "localhost";
        private int port = 11211;
        private int defaultTtl = 300;
    }

    @Getter @Setter
    public static class Encryption {
        private String key;
    }

    @Getter @Setter
    public static class Cloudflare {
        private String accountId;
        private String queueId;
        private String apiToken;
    }

    @Getter @Setter
    public static class Resend {
        private String apiKey;
        private String from = "noreply@cryptostate.app";
    }

    @Getter @Setter
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String model = "qwen3";
    }

    @Getter @Setter
    public static class RateLimit {
        private Bucket auth = new Bucket(5, 15);
        private Bucket sync = new Bucket(3, 1);

        @Getter @Setter
        public static class Bucket {
            private int capacity;
            private int refillDurationMinutes;

            public Bucket() {}
            public Bucket(int capacity, int refillDurationMinutes) {
                this.capacity = capacity;
                this.refillDurationMinutes = refillDurationMinutes;
            }
        }
    }
}
