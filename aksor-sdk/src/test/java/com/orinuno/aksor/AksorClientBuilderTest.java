package com.orinuno.aksor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.aksor.host.AksorHostPageParser;
import com.orinuno.aksor.model.AksorAnime;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class AksorClientBuilderTest {

    private static AksorHostPageParser fake(String id, String suffix) {
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
                return Mono.empty();
            }
        };
    }

    @Test
    void noArgBuildIncludesYummyAniHost() {
        AksorClient client = AksorClient.builder().build();
        assertThat(client.hostRegistry().hosts())
                .extracting(AksorHostPageParser::hostId)
                .containsExactly("yummyani");
    }

    @Test
    void registerHostAppendsAfterDefault() {
        AksorClient client = AksorClient.builder().registerHost(fake("custom", "x.test")).build();
        assertThat(client.hostRegistry().hosts())
                .extracting(AksorHostPageParser::hostId)
                .containsExactly("yummyani", "custom");
    }

    @Test
    void replaceHostsDropsDefault() {
        AksorClient client =
                AksorClient.builder().replaceHosts(List.of(fake("only", "y.test"))).build();
        assertThat(client.hostRegistry().hosts())
                .extracting(AksorHostPageParser::hostId)
                .containsExactly("only");
    }

    @Test
    void replaceHostsRejectsEmpty() {
        assertThatThrownBy(() -> AksorClient.builder().replaceHosts(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
