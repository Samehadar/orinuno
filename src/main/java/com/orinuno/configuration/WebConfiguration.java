package com.orinuno.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.CorsRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfiguration implements WebFluxConfigurer {

    private final OrinunoProperties properties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var origins = properties.getCors().getAllowedOrigins();
        boolean isWildcard = origins.size() == 1 && "*".equals(origins.get(0));

        var mapping = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");

        if (isWildcard) {
            mapping.allowedOriginPatterns("*").allowCredentials(false);
        } else {
            mapping.allowedOrigins(origins.toArray(String[]::new)).allowCredentials(true);
        }
    }
}
