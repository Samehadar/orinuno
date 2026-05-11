package com.orinuno.aksor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.aksor.model.AksorAnime;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AksorHostRegistryTest {

    private static AksorHostPageParser stub(String id, String suffix) {
        return new AksorHostPageParser() {
            @Override
            public String hostId() {
                return id;
            }

            @Override
            public boolean supports(URI pageUrl) {
                return pageUrl != null
                        && pageUrl.getHost() != null
                        && pageUrl.getHost().endsWith(suffix);
            }

            @Override
            public Mono<AksorAnime> resolve(String pageUrl) {
                throw new UnsupportedOperationException("stub");
            }
        };
    }

    @Test
    void firstMatchWins() {
        AksorHostRegistry r =
                new AksorHostRegistry(
                        List.of(stub("yummyani", "yummyani.me"), stub("foo", "foo.test")));
        assertThat(r.resolve("https://yummyani.me/x").map(AksorHostPageParser::hostId))
                .contains("yummyani");
        assertThat(r.resolve("https://foo.test/y").map(AksorHostPageParser::hostId))
                .contains("foo");
    }

    @Test
    void unsupportedReturnsEmpty() {
        AksorHostRegistry r = new AksorHostRegistry(List.of(stub("a", "a.test")));
        assertThat(r.resolve("https://other.test")).isEmpty();
        assertThat(r.resolve("")).isEmpty();
        assertThat(r.resolve((String) null)).isEmpty();
    }

    @Test
    void emptyHostsRejected() {
        assertThatThrownBy(() -> new AksorHostRegistry(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
