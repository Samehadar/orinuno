package com.orinuno.cvh.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.cvh.model.AnimeContent;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class CvhHostRegistryTest {

    private static CvhHostPageParser stub(String id, String hostSuffix) {
        return new CvhHostPageParser() {
            @Override
            public String hostId() {
                return id;
            }

            @Override
            public boolean supports(URI pageUrl) {
                return pageUrl != null
                        && pageUrl.getHost() != null
                        && pageUrl.getHost().endsWith(hostSuffix);
            }

            @Override
            public AnimeContent parse(String html, String pageUrl) {
                throw new UnsupportedOperationException("stub");
            }
        };
    }

    @Test
    void resolvesByUrl() {
        CvhHostRegistry r =
                new CvhHostRegistry(
                        List.of(stub("jutsu", "jut-su.works"), stub("foo", "foo.test")));
        assertThat(r.resolve("https://jut-su.works/anime").map(CvhHostPageParser::hostId))
                .contains("jutsu");
        assertThat(r.resolve("https://foo.test/x").map(CvhHostPageParser::hostId)).contains("foo");
    }

    @Test
    void unsupportedHostReturnsEmpty() {
        CvhHostRegistry r = new CvhHostRegistry(List.of(stub("jutsu", "jut-su.works")));
        assertThat(r.resolve("https://other.test/")).isEmpty();
        assertThat(r.resolve((String) null)).isEmpty();
        assertThat(r.resolve("")).isEmpty();
    }

    @Test
    void firstRegisteredWins() {
        CvhHostRegistry r =
                new CvhHostRegistry(List.of(stub("alpha", "foo.test"), stub("beta", "foo.test")));
        assertThat(r.resolve("https://foo.test/").map(CvhHostPageParser::hostId)).contains("alpha");
    }

    @Test
    void emptyHostsRejected() {
        assertThatThrownBy(() -> new CvhHostRegistry(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostsAccessorIsImmutable() {
        CvhHostRegistry r = new CvhHostRegistry(List.of(stub("a", "foo.test")));
        assertThatThrownBy(() -> r.hosts().add(stub("b", "bar.test")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
