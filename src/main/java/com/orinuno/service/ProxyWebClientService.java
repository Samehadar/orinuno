package com.orinuno.service;

import com.orinuno.model.KodikProxy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;

import java.util.Optional;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyWebClientService {

    private final ProxyProviderService proxyProviderService;

    public WebClient createWebClient() {
        Optional<KodikProxy> proxyOpt = proxyProviderService.getNextProxy();

        if (proxyOpt.isEmpty()) {
            return WebClient.builder().build();
        }

        return buildProxiedWebClient(proxyOpt.get());
    }

    public <T> Mono<T> executeWithProxyFallback(
            WebClient directClient,
            Function<WebClient, Mono<T>> requestFn
    ) {
        Optional<KodikProxy> proxyOpt = proxyProviderService.getNextProxy();

        if (proxyOpt.isEmpty()) {
            return requestFn.apply(directClient);
        }

        KodikProxy proxy = proxyOpt.get();
        WebClient proxiedClient = buildProxiedWebClient(proxy);

        return requestFn.apply(proxiedClient)
                .onErrorResume(e -> {
                    log.warn("Proxy {}:{} failed ({}), falling back to direct connection",
                            proxy.getHost(), proxy.getPort(), e.getMessage());
                    proxyProviderService.reportFailure(proxy.getId());
                    return requestFn.apply(directClient);
                });
    }

    private WebClient buildProxiedWebClient(KodikProxy proxy) {
        log.debug("Creating WebClient with proxy {}:{}", proxy.getHost(), proxy.getPort());

        HttpClient httpClient = HttpClient.create()
                .proxy(proxySpec -> {
                    var builder = proxySpec
                            .type(toNettyProxyType(proxy.getProxyType()))
                            .host(proxy.getHost())
                            .port(proxy.getPort());

                    if (proxy.getUsername() != null && !proxy.getUsername().isBlank()) {
                        builder.username(proxy.getUsername())
                                .password(ignored -> proxy.getPassword());
                    }
                });

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    private ProxyProvider.Proxy toNettyProxyType(KodikProxy.ProxyType type) {
        return switch (type) {
            case SOCKS5 -> ProxyProvider.Proxy.SOCKS5;
            case HTTP -> ProxyProvider.Proxy.HTTP;
        };
    }
}
