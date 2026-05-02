package com.orinuno.client.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RotatingUserAgentProviderTest {

    @Test
    void desktopPoolHasCanonicalChromeAndFirefoxStrings() {
        assertThat(RotatingUserAgentProvider.DESKTOP_POOL)
                .anyMatch(ua -> ua.contains("Chrome/135") && ua.contains("Windows NT 10"))
                .anyMatch(ua -> ua.contains("Chrome/135") && ua.contains("Macintosh"))
                .anyMatch(ua -> ua.contains("Chrome/135") && ua.contains("Linux x86_64"))
                .anyMatch(ua -> ua.contains("Firefox/128") && ua.contains("Windows"))
                .anyMatch(ua -> ua.contains("Firefox/128") && ua.contains("Macintosh"));
    }

    @Test
    void desktopPoolHasFiveEntries() {
        assertThat(RotatingUserAgentProvider.DESKTOP_POOL).hasSize(5);
    }

    @Test
    void randomDesktopAlwaysReturnsAValueFromPool() {
        RotatingUserAgentProvider provider = new RotatingUserAgentProvider();
        for (int i = 0; i < 200; i++) {
            assertThat(RotatingUserAgentProvider.DESKTOP_POOL).contains(provider.randomDesktop());
        }
    }

    @Test
    void randomDesktopEventuallyHitsMostOfPool() {
        RotatingUserAgentProvider provider = new RotatingUserAgentProvider();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            seen.add(provider.randomDesktop());
        }
        assertThat(seen).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void stableDesktopReturnsSameValueAcrossCalls() {
        RotatingUserAgentProvider provider = new RotatingUserAgentProvider();
        String first = provider.stableDesktop();
        for (int i = 0; i < 50; i++) {
            assertThat(provider.stableDesktop()).isEqualTo(first);
        }
    }

    @Test
    void stableDesktopValueIsFromPool() {
        RotatingUserAgentProvider provider = new RotatingUserAgentProvider();
        assertThat(RotatingUserAgentProvider.DESKTOP_POOL).contains(provider.stableDesktop());
    }

    @Test
    void orinunoBotAppendsSuffix() {
        assertThat(RotatingUserAgentProvider.orinunoBot("calendar-watcher"))
                .isEqualTo("orinuno/1.0 (+https://github.com/orinuno) calendar-watcher");
    }

    @Test
    void orinunoBotRejectsNullSuffix() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> RotatingUserAgentProvider.orinunoBot(null))
                .isInstanceOf(NullPointerException.class);
    }
}
