package com.orinuno.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "orinuno")
public class OrinunoProperties {

    private KodikProperties kodik = new KodikProperties();
    private ParseProperties parse = new ParseProperties();
    private DecoderProperties decoder = new DecoderProperties();
    private ProxyProperties proxy = new ProxyProperties();
    private StorageProperties storage = new StorageProperties();

    @Data
    public static class KodikProperties {
        private String apiUrl = "https://kodik-api.com";
        private String token = "";
        private long requestDelayMs = 500;
    }

    @Data
    public static class ParseProperties {
        private int rateLimitPerMinute = 30;
    }

    @Data
    public static class DecoderProperties {
        private int timeoutSeconds = 30;
        private int maxRetries = 3;
        private int linkTtlHours = 20;
        private long refreshIntervalMs = 3600000;
        private int refreshBatchSize = 50;
    }

    private PlaywrightProperties playwright = new PlaywrightProperties();
    private SecurityProperties security = new SecurityProperties();
    private CorsProperties cors = new CorsProperties();

    @Data
    public static class SecurityProperties {
        private String apiKey = "";
    }

    @Data
    public static class CorsProperties {
        private List<String> allowedOrigins = List.of("*");
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String rotationStrategy = "round-robin";
    }

    @Data
    public static class StorageProperties {
        private String basePath = "./data/videos";
        private long maxDiskUsageMb = 10240;
    }

    @Data
    public static class PlaywrightProperties {
        private boolean enabled = true;
        private boolean headless = true;
        private int pageTimeoutSeconds = 30;
        private int navigationTimeoutMs = 15000;
        private int videoWaitMs = 30000;
        private int hlsConcurrency = 16;
    }
}
