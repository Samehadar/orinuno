package com.orinuno.service;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikProxy;
import com.orinuno.repository.ProxyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProxyProviderServiceTest {

    @Mock
    private ProxyRepository proxyRepository;

    private ProxyProviderService proxyProviderService;

    @BeforeEach
    void setUp() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getProxy().setEnabled(true);
        proxyProviderService = new ProxyProviderService(proxyRepository, properties);
    }

    @Test
    @DisplayName("Should return proxy in round-robin order")
    void shouldReturnProxyRoundRobin() {
        KodikProxy proxy1 = KodikProxy.builder().id(1L).host("proxy1.com").port(8080).build();
        KodikProxy proxy2 = KodikProxy.builder().id(2L).host("proxy2.com").port(8080).build();

        when(proxyRepository.findAllActive()).thenReturn(List.of(proxy1, proxy2));

        Optional<KodikProxy> first = proxyProviderService.getNextProxy();
        Optional<KodikProxy> second = proxyProviderService.getNextProxy();
        Optional<KodikProxy> third = proxyProviderService.getNextProxy();

        assertThat(first).isPresent();
        assertThat(second).isPresent();
        assertThat(third).isPresent();
        assertThat(first.get().getHost()).isEqualTo("proxy1.com");
        assertThat(second.get().getHost()).isEqualTo("proxy2.com");
        assertThat(third.get().getHost()).isEqualTo("proxy1.com");
    }

    @Test
    @DisplayName("Should return empty when proxy disabled")
    void shouldReturnEmptyWhenDisabled() {
        OrinunoProperties props = new OrinunoProperties();
        props.getProxy().setEnabled(false);
        ProxyProviderService service = new ProxyProviderService(proxyRepository, props);

        assertThat(service.getNextProxy()).isEmpty();
        verify(proxyRepository, never()).findAllActive();
    }

    @Test
    @DisplayName("Should return empty when no active proxies")
    void shouldReturnEmptyWhenNoProxies() {
        when(proxyRepository.findAllActive()).thenReturn(List.of());
        assertThat(proxyProviderService.getNextProxy()).isEmpty();
    }
}
