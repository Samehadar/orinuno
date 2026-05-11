package com.orinuno.token;

import com.kodik.token.KodikTokenConfig;
import com.orinuno.configuration.OrinunoProperties;

/**
 * Test-side bridge from OrinunoProperties to {@link KodikTokenConfig}. Mirrors the production
 * translation in {@code orinuno-app/.../KodikSdkConfiguration.kodikTokenConfig} so tests can keep
 * mutating their {@code properties.getKodik()} fixtures and pick up a fresh config at registry /
 * validator construction time.
 */
public final class TokenConfigTestSupport {

    private TokenConfigTestSupport() {}

    public static KodikTokenConfig toConfig(OrinunoProperties properties) {
        OrinunoProperties.KodikProperties k = properties.getKodik();
        return KodikTokenConfig.builder()
                .tokenFile(k.getTokenFile())
                .bootstrapToken(k.getToken())
                .bootstrapFromEnv(k.isBootstrapFromEnv())
                .autoDiscoveryEnabled(k.isAutoDiscoveryEnabled())
                .validateOnStartup(k.isValidateOnStartup())
                .deadRevalidationIntervalMinutes(k.getDeadRevalidationIntervalMinutes())
                .tokenFailoverMaxAttempts(k.getTokenFailoverMaxAttempts())
                .build();
    }
}
