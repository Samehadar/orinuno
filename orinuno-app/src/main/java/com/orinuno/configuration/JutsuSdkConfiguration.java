package com.orinuno.configuration;

import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring wiring for the {@code jutsu-sdk} module (Step 2 of the API/module split).
 *
 * <p>The SDK has zero Spring auto-configuration. We translate {@link OrinunoProperties} into a
 * {@link JutsuConfig}, hand it to {@link JutsuClient.Builder}, then re-expose the SDK's rate
 * limiter and session manager as Spring beans so the rest of orinuno-app (controllers, tests) can
 * inject them as before — only the package import changes.
 *
 * <p>The {@link JutsuConfig} is rebuilt every time we read {@code OrinunoProperties} via the {@link
 * JutsuRateLimiter}'s supplier, which means the {@code orinuno.providers.jutsu.rate-limit-rps}
 * property hot-reloads exactly the same way it did before extraction.
 */
@Configuration
public class JutsuSdkConfiguration {

    /**
     * Build the canonical {@link JutsuConfig} from {@link OrinunoProperties}. This is a snapshot of
     * the credentials/base-url/UA at startup; the rate-limit RPS is re-read on every acquire via
     * {@link JutsuRateLimiter}'s supplier so live config reloads still take effect there.
     */
    @Bean
    public JutsuConfig jutsuConfig(
            OrinunoProperties properties, RotatingUserAgentProvider userAgents) {
        OrinunoProperties.JutsuProperties jp = properties.getProviders().getJutsu();
        return JutsuConfig.builder()
                .baseUrl(jp.getBaseUrl())
                .credentials(jp.getUsername(), jp.getPassword())
                .userAgent(userAgents.stableDesktop())
                .rateLimitRps(jp.getRateLimitRps())
                .sessionTtl(Duration.ofMinutes(Math.max(1, jp.getSessionTtlMinutes())))
                .loginTimeout(Duration.ofSeconds(Math.max(1, jp.getLoginTimeoutSeconds())))
                .build();
    }

    @Bean
    public JutsuRateLimiter jutsuRateLimiter(
            OrinunoProperties properties, MeterRegistry meterRegistry) {
        // Pull RPS through the live properties bean rather than freezing it at startup so a
        // config-server hot reload of orinuno.providers.jutsu.rate-limit-rps still rebuilds the
        // bucket. The SDK's JutsuRateLimiter notices supplier changes on next acquire().
        return new JutsuRateLimiter(
                () -> properties.getProviders().getJutsu().getRateLimitRps(), meterRegistry);
    }

    @Bean
    public JutsuSessionManager jutsuSessionManager(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {
        return new JutsuSessionManager(config, rateLimiter, webClientBuilder, meterRegistry);
    }

    /**
     * The high-level facade. Most callers prefer this; the lower-level beans above are exposed for
     * the cases where we need direct access to the cookie jar (CDN proxy) or the bucket (RPS
     * sharing).
     */
    @Bean
    public JutsuClient jutsuClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            WebClient.Builder webClientBuilder) {
        // We pass the same singletons we just registered so the orchestrator and the
        // controller/proxy-controller side share the same buckets and cookie jars. Building a
        // new JutsuClient here would silently double the outbound RPS budget.
        return new JutsuClient(config, rateLimiter, sessionManager, webClientBuilder);
    }
}
