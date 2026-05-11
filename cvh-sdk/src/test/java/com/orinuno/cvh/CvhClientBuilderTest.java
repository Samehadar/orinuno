package com.orinuno.cvh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.cvh.host.CvhHostPageParser;
import com.orinuno.cvh.model.AnimeContent;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class CvhClientBuilderTest {

    private static CvhHostPageParser fakeHost(String id, String suffix) {
        return new CvhHostPageParser() {
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
            public AnimeContent parse(String html, String pageUrl) {
                return new AnimeContent(
                        null, pageUrl, null, null, null, List.of(), null, null, null, null, null,
                        null, null, null, null);
            }
        };
    }

    @Test
    void noArgBuildIncludesDefaultJutsuHost() {
        CvhClient client = CvhClient.builder().build();
        assertThat(client.hostRegistry().hosts())
                .extracting(CvhHostPageParser::hostId)
                .containsExactly("jutsu");
    }

    @Test
    void registerHostAppendsAfterDefault() {
        CvhClient client = CvhClient.builder().registerHost(fakeHost("custom", "foo.test")).build();
        assertThat(client.hostRegistry().hosts())
                .extracting(CvhHostPageParser::hostId)
                .containsExactly("jutsu", "custom");
    }

    @Test
    void replaceHostsDropsDefault() {
        CvhClient client =
                CvhClient.builder().replaceHosts(List.of(fakeHost("only", "x.test"))).build();
        assertThat(client.hostRegistry().hosts())
                .extracting(CvhHostPageParser::hostId)
                .containsExactly("only");
    }

    @Test
    void replaceHostsRejectsEmpty() {
        assertThatThrownBy(() -> CvhClient.builder().replaceHosts(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerHostRejectsNull() {
        assertThatThrownBy(() -> CvhClient.builder().registerHost(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configFlowsThroughToClient() {
        CvhConfig cfg = CvhConfig.builder().defaultAggregator("foo").build();
        CvhClient client = CvhClient.builder().config(cfg).build();
        assertThat(client.config().defaultAggregator()).isEqualTo("foo");
    }
}
