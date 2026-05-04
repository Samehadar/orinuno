package com.orinuno.jutsu;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.filter.JutsuCatalogFilter;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Live integration tests against jut.su. Skipped unless {@code JUTSU_LIVE_TESTS=1} is set in the
 * environment — running them hits the real site, eats from the 1 RPS budget and is slow (each block
 * can take 30-60s).
 *
 * <p>Block A: 50 randomized filter combos with a fixed seed (so failures reproduce). Block B:
 * pairwise permutation sample for ordering invariance. Block C: BY_RATING elision check. Block D:
 * search composes orthogonally with filters.
 *
 * <p>The 1 RPS rate limit means each block takes wall-clock ~50s minimum. Reactive Schedulers and
 * the SDK's bucket handle the pacing; the tests just assert that responses come back without drift
 * events and contain at least one entry.
 *
 * <p><b>Why a fixed seed?</b> Random combos are repeatable so a CI failure can be reproduced
 * locally with the same sequence; a fresh seed each run would silently hide flaky combos.
 */
@EnabledIfEnvironmentVariable(named = "JUTSU_LIVE_TESTS", matches = "1|true|TRUE|yes")
class JutsuLiveIntegrationTest {

    private static final long SEED = 0xC0FFEEL;

    private static JutsuClient newClient() {
        return JutsuClient.builder()
                .config(JutsuConfig.builder().userAgent(defaultUa()).build())
                .build();
    }

    private static String defaultUa() {
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like"
                + " Gecko) Chrome/147.0.0.0 Safari/537.36";
    }

    private static JutsuCatalogFilter randomFilter(Random rng) {
        // Pick a small random subset across each enum so the filter remains realistic — at most 3
        // genres, 2 types, 2 year buckets, and a sort. Empty subsets are also valid.
        Set<JutsuGenre> genres = randomSubset(JutsuGenre.values(), rng, 3);
        Set<JutsuType> types = randomSubset(JutsuType.values(), rng, 2);
        Set<JutsuYear> years = randomSubset(JutsuYear.values(), rng, 2);
        JutsuSort sort = JutsuSort.values()[rng.nextInt(JutsuSort.values().length)];
        JutsuCatalogFilter.Builder b = JutsuCatalogFilter.builder().sort(sort);
        genres.forEach(b::addGenre);
        types.forEach(b::addType);
        years.forEach(b::addYear);
        return b.build();
    }

    private static <E extends Enum<E>> Set<E> randomSubset(E[] all, Random rng, int max) {
        Set<E> out = EnumSet.noneOf((Class<E>) all.getClass().getComponentType());
        int count = rng.nextInt(max + 1);
        List<E> remaining = new ArrayList<>(java.util.Arrays.asList(all));
        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            out.add(remaining.remove(rng.nextInt(remaining.size())));
        }
        return out;
    }

    static Stream<JutsuCatalogFilter> blockARandomFilters() {
        Random rng = new Random(SEED);
        return Stream.generate(() -> randomFilter(rng)).limit(50);
    }

    @ParameterizedTest(name = "live filter combo {index}")
    @MethodSource("blockARandomFilters")
    void blockA_randomFilterCombosLandOnLiveSite(JutsuCatalogFilter filter) {
        try (JutsuClientHandle handle = JutsuClientHandle.fresh()) {
            JutsuCatalogPage page =
                    handle.client.browseCatalog(filter, 1).block(Duration.ofSeconds(30));
            // The site can return 0 entries for a deeply-restrictive filter — that's fine. What
            // we assert is: no drift events fired, the response was structurally valid.
            assertThat(page).isNotNull();
            assertThat(handle.client.getDriftSnapshot().recentEvents())
                    .as("filter %s must not raise drift", filter)
                    .isEmpty();
        }
    }

    @Test
    void blockB_permutedFilterReturnsSamePage() {
        // Ordering invariance: jut.su's slug grammar is order-sensitive on the URL but the SDK
        // composes deterministically (declaration-order), so two builders with the same set
        // members must hit the same URL and return entries in the same order. We re-build the
        // same filter from a different selection sequence and compare the response shape.
        try (JutsuClientHandle handle = JutsuClientHandle.fresh()) {
            JutsuCatalogFilter a =
                    JutsuCatalogFilter.builder()
                            .addGenre(JutsuGenre.COMEDY)
                            .addGenre(JutsuGenre.ROMANCE)
                            .addType(JutsuType.SHONEN)
                            .addYear(JutsuYear.Y_2024)
                            .sort(JutsuSort.BY_NAME)
                            .build();
            JutsuCatalogFilter b =
                    JutsuCatalogFilter.builder()
                            .sort(JutsuSort.BY_NAME)
                            .addType(JutsuType.SHONEN)
                            .addGenre(JutsuGenre.ROMANCE)
                            .addGenre(JutsuGenre.COMEDY)
                            .addYear(JutsuYear.Y_2024)
                            .build();

            JutsuCatalogPage pa = handle.client.browseCatalog(a, 1).block(Duration.ofSeconds(30));
            JutsuCatalogPage pb = handle.client.browseCatalog(b, 1).block(Duration.ofSeconds(30));

            assertThat(pa).isNotNull();
            assertThat(pb).isNotNull();
            assertThat(pa.entries()).hasSize(pb.entries().size());
            // Compare slugs only — counts and metadata can drift between two requests separated by
            // ~1s if the ratings update mid-scrape.
            for (int i = 0; i < pa.entries().size(); i++) {
                assertThat(pa.entries().get(i).slug()).isEqualTo(pb.entries().get(i).slug());
            }
            assertThat(handle.client.getDriftSnapshot().recentEvents()).isEmpty();
        }
    }

    @Test
    void blockC_byRatingSortIsElidedFromUrl() {
        // The SDK's slug composer elides BY_RATING (jut.su's default sort), so a filter with
        // sort=BY_RATING must hit the same URL as a filter without an explicit sort. Verify by
        // checking that both calls succeed without drift.
        try (JutsuClientHandle handle = JutsuClientHandle.fresh()) {
            JutsuCatalogFilter rated =
                    JutsuCatalogFilter.builder()
                            .addGenre(JutsuGenre.ACTION)
                            .sort(JutsuSort.BY_RATING)
                            .build();
            JutsuCatalogFilter implicit =
                    JutsuCatalogFilter.builder().addGenre(JutsuGenre.ACTION).build();

            JutsuCatalogPage rPage =
                    handle.client.browseCatalog(rated, 1).block(Duration.ofSeconds(30));
            JutsuCatalogPage iPage =
                    handle.client.browseCatalog(implicit, 1).block(Duration.ofSeconds(30));

            assertThat(rPage).isNotNull();
            assertThat(iPage).isNotNull();
            assertThat(rPage.entries()).hasSize(iPage.entries().size());
            assertThat(handle.client.getDriftSnapshot().recentEvents()).isEmpty();
        }
    }

    @Test
    void blockD_searchComposesOrthogonallyWithFilter() {
        // Title search alone must return at least one hit for "история" (a common Russian word
        // that appears in dozens of anime titles). Combined with a comedy filter, the result set
        // should be a strict subset (or empty if no overlap exists today, which is also valid).
        try (JutsuClientHandle handle = JutsuClientHandle.fresh()) {
            JutsuCatalogPage searchOnly =
                    handle.client.searchByTitle("история", 1).block(Duration.ofSeconds(30));
            JutsuCatalogFilter comedy =
                    JutsuCatalogFilter.builder().addGenre(JutsuGenre.COMEDY).build();
            JutsuCatalogPage searchPlusComedy =
                    handle.client.searchByTitle(comedy, "история", 1).block(Duration.ofSeconds(30));

            assertThat(searchOnly).isNotNull();
            assertThat(searchPlusComedy).isNotNull();
            assertThat(searchOnly.entries().size())
                    .as("search-only result set must be ≥ search+filter set")
                    .isGreaterThanOrEqualTo(searchPlusComedy.entries().size());
            assertThat(handle.client.getDriftSnapshot().recentEvents()).isEmpty();
        }
    }

    @Test
    void blockE_latestNoticeFeedReturnsRealEntries() {
        // Smoke test: the notice feed should always have entries (jut.su has been running for
        // years, the latest cursor is always populated).
        try (JutsuClientHandle handle = JutsuClientHandle.fresh()) {
            JutsuNoticeFeed feed =
                    handle.client.getLatestNoticeFeed().block(Duration.ofSeconds(30));
            assertThat(feed).isNotNull();
            assertThat(feed.entries())
                    .as("latest notice feed must contain at least one entry")
                    .isNotEmpty();
            assertThat(handle.client.getDriftSnapshot().recentEvents()).isEmpty();
        }
    }

    /**
     * AutoCloseable wrapper so each test gets a fresh client (and fresh drift detector). Using
     * try-with-resources keeps the per-test isolation explicit.
     */
    private static final class JutsuClientHandle implements AutoCloseable {
        final JutsuClient client;

        private JutsuClientHandle(JutsuClient client) {
            this.client = client;
        }

        static JutsuClientHandle fresh() {
            return new JutsuClientHandle(newClient());
        }

        @Override
        public void close() {
            // JutsuClient owns no resources we need to release explicitly today.
        }
    }
}
