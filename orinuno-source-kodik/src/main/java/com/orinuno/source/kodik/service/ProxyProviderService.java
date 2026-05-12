/*
 * ProxyProviderService — ADR 0021 §D1b-2.
 *
 * Round-robin proxy selector backing the Kodik decoder's CDN-fetch path.
 * Ported from orinuno-app's ProxyProviderService — the legacy
 * OrinunoProperties.ProxyProperties.enabled flag is replaced by a single
 * @Value-injected knob (orinuno.source-kodik.proxy.enabled, default
 * false) since that's the only field the legacy service read.
 */
package com.orinuno.source.kodik.service;

import com.orinuno.source.kodik.model.KodikProxy;
import com.orinuno.source.kodik.repository.ProxyRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ProxyProviderService {

    private final ProxyRepository proxyRepository;
    private final boolean proxyEnabled;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public ProxyProviderService(
            ProxyRepository proxyRepository,
            @Value("${orinuno.source-kodik.proxy.enabled:false}") boolean proxyEnabled) {
        this.proxyRepository = proxyRepository;
        this.proxyEnabled = proxyEnabled;
    }

    public Optional<KodikProxy> getNextProxy() {
        if (!proxyEnabled) {
            return Optional.empty();
        }

        List<KodikProxy> activeProxies = proxyRepository.findAllActive();
        if (activeProxies.isEmpty()) {
            log.warn("No active proxies available");
            return Optional.empty();
        }

        int index = roundRobinIndex.getAndUpdate(i -> (i + 1) % activeProxies.size());
        KodikProxy proxy = activeProxies.get(index);

        proxyRepository.updateLastUsedAt(proxy.getId());
        log.debug("Selected proxy: {}:{}", proxy.getHost(), proxy.getPort());

        return Optional.of(proxy);
    }

    public void reportFailure(Long proxyId) {
        proxyRepository.incrementFailCount(proxyId);
        log.warn("Proxy failure reported for id={}", proxyId);
    }

    public void disableProxy(Long proxyId) {
        proxyRepository.updateStatus(proxyId, KodikProxy.ProxyStatus.DISABLED.name());
        log.warn("Proxy disabled: id={}", proxyId);
    }

    public List<KodikProxy> getActiveProxies() {
        return proxyRepository.findAllActive();
    }
}
