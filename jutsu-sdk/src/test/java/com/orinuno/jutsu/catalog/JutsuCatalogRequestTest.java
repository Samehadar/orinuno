package com.orinuno.jutsu.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuYear;
import org.junit.jupiter.api.Test;

class JutsuCatalogRequestTest {

    @Test
    void unfilteredFactoryUsesEmptyFilter() {
        JutsuCatalogRequest req = JutsuCatalogRequest.unfiltered(2);

        assertThat(req.filter()).isEqualTo(JutsuCatalogFilter.empty());
        assertThat(req.page()).isEqualTo(2);
        assertThat(req.searchQuery()).isNull();
        assertThat(req.pathPrefix()).isNull();
        assertThat(req.resolvePath()).isEqualTo("/anime/");
        assertThat(req.hasSearch()).isFalse();
    }

    @Test
    void filteredFactoryComposesPathFromFilter() {
        JutsuCatalogFilter filter =
                JutsuCatalogFilter.builder()
                        .addGenre(JutsuGenre.COMEDY)
                        .addYear(JutsuYear.Y_2024)
                        .sort(JutsuSort.BY_DATE_ADDED)
                        .build();

        JutsuCatalogRequest req = JutsuCatalogRequest.filtered(filter, 1);

        assertThat(req.resolvePath()).isEqualTo("/anime/comedy/2024/order-by-add/");
    }

    @Test
    void searchFactoryNormalisesEmptyToNullAndTrimsWhitespace() {
        assertThat(JutsuCatalogRequest.search("  ", 1).searchQuery()).isNull();
        assertThat(JutsuCatalogRequest.search("  история  ", 1).searchQuery()).isEqualTo("история");
    }

    @Test
    void searchInFilterCarriesBothFilterAndQuery() {
        JutsuCatalogFilter filter = JutsuCatalogFilter.builder().addGenre(JutsuGenre.DRAMA).build();

        JutsuCatalogRequest req = JutsuCatalogRequest.searchInFilter(filter, "история", 3);

        assertThat(req.filter()).isEqualTo(filter);
        assertThat(req.searchQuery()).isEqualTo("история");
        assertThat(req.resolvePath()).isEqualTo("/anime/drama/");
        assertThat(req.hasSearch()).isTrue();
    }

    @Test
    void pathPrefixOverridesFilter() {
        JutsuCatalogRequest req =
                JutsuCatalogRequest.withPathPrefix("/anime/some-experimental-path/", 2);

        assertThat(req.resolvePath()).isEqualTo("/anime/some-experimental-path/");
    }

    @Test
    void pathPrefixIsNormalisedWithLeadingAndTrailingSlash() {
        JutsuCatalogRequest req = JutsuCatalogRequest.withPathPrefix("anime/foo", 1);

        assertThat(req.resolvePath()).isEqualTo("/anime/foo/");
    }

    @Test
    void invalidPageThrows() {
        assertThatIllegalArgumentException().isThrownBy(() -> JutsuCatalogRequest.unfiltered(0));
        assertThatIllegalArgumentException().isThrownBy(() -> JutsuCatalogRequest.unfiltered(-1));
    }

    @Test
    void nullFilterRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JutsuCatalogRequest(null, 1, null, null));
    }

    @Test
    void formBodyEncodesQueryAsUrlEncodedUtf8() {
        String body = JutsuCatalogClient.composeFormBody(JutsuCatalogRequest.search("история", 1));

        assertThat(body).startsWith("ajax_load=yes&start_from_page=1&show_search=");
        assertThat(body).endsWith("&anime_of_user=");
        // %D0%B8%D1%81%D1%82%D0%BE%D1%80%D0%B8%D1%8F is URL-encoded UTF-8 for "история".
        assertThat(body).contains("show_search=%D0%B8%D1%81%D1%82%D0%BE%D1%80%D0%B8%D1%8F");
    }

    @Test
    void formBodyForUnfilteredRequestHasEmptyShowSearch() {
        String body = JutsuCatalogClient.composeFormBody(JutsuCatalogRequest.unfiltered(2));

        assertThat(body).contains("&show_search=&anime_of_user=");
        assertThat(body).contains("&start_from_page=2&");
    }

    @Test
    void formBodyTreatsBlankQueryAsNoSearch() {
        // search() trims to null, so even a "  " input doesn't end up in the body.
        String body = JutsuCatalogClient.composeFormBody(JutsuCatalogRequest.search("   ", 1));

        assertThat(body).contains("&show_search=&anime_of_user=");
    }
}
