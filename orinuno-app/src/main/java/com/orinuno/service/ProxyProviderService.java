package com.orinuno.service;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikProxy;
import com.orinuno.repository.ProxyRepository;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyProviderService {

    private final ProxyRepository proxyRepository;
    private final OrinunoProperties properties;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public Optional<KodikProxy> getNextProxy() {
        if (!properties.getProxy().isEnabled()) {
            return Optional.empty();
        }

        List<KodikProxy> activeProxies = proxyRepository.findAllActive();
        if (activeProxies.isEmpty()) {
            log.warn("⚠️ No active proxies available");
            return Optional.empty();
        }

        int index = roundRobinIndex.getAndUpdate(i -> (i + 1) % activeProxies.size());
        KodikProxy proxy = activeProxies.get(index);

        proxyRepository.updateLastUsedAt(proxy.getId());
        log.debug("🔄 Selected proxy: {}:{}", proxy.getHost(), proxy.getPort());

        return Optional.of(proxy);
    }

    public void reportFailure(Long proxyId) {
        proxyRepository.incrementFailCount(proxyId);
        log.warn("⚠️ Proxy failure reported for id={}", proxyId);
    }

    public void disableProxy(Long proxyId) {
        proxyRepository.updateStatus(proxyId, KodikProxy.ProxyStatus.DISABLED.name());
        log.warn("🚫 Proxy disabled: id={}", proxyId);
    }

    public List<KodikProxy> getActiveProxies() {
        return proxyRepository.findAllActive();
    }
}
