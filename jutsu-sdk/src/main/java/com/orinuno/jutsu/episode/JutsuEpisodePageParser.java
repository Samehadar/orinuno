package com.orinuno.jutsu.episode;

import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import jakarta.annotation.Nullable;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Parses {@code GET /{slug}/(season-N/)?episode-M.html} or {@code GET /{slug}/film-N.html} into a
 * {@link JutsuPageMeta}.
 *
 * <p>The viewer page is the same template for episodes and full-length movies — same chrome,
 * paywall mechanic, navigation arrows and "all episodes" link — and only the URL grammar
 * discriminates the two. This parser extracts the cheap chrome (title, thumbnail, prev/next arrows,
 * paywall flag) without invoking the heavier video-decoder pipeline. The decoder regex-based path
 * in {@code JutsuDecoder} remains the single source of truth for actual video URL extraction.
 */
public final class JutsuEpisodePageParser {

    /** Episode URL grammar — same shape as {@code JutsuAnimeInfoParser}. */
    private static final Pattern EPISODE_URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/(?:season-(\\d+)/)?episode-(\\d+)\\.html");

    /**
     * Full-length movie URL grammar; aligned with {@code JutsuAnimeInfoParser.FILM_URL_PATTERN}.
     */
    private static final Pattern FILM_URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/film-(\\d+)\\.html");

    static final String CANONICAL_SELECTOR = "link[rel='canonical']";

    static final String OG_IMAGE_SELECTOR = "meta[property='og:image']";

    static final String TITLE_SELECTOR = "title";

    static final String H1_SELECTOR = "h1";

    static final String NEXT_EPISODE_SELECTOR = "a.vnright";

    static final String PREV_EPISODE_SELECTOR = "a.vnleft";

    static final String ALL_EPISODES_SELECTOR = "a.vncenter";

    static final String PAYWALL_SELECTOR = ".tab_need_plus";

    private final JutsuParserContext ctx;

    public JutsuEpisodePageParser(JutsuParserContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx must not be null");
        this.ctx = ctx;
    }

    /**
     * Parse the response body. {@code expectedUrl} is the relative URL the SDK requested; the
     * parser cross-checks it against the page's canonical link to detect URL drift (different
     * slug/season/episode/film index would mean jut.su silently redirected us to a different page).
     * When {@code expectedUrl} is {@code null}, no cross-check is performed.
     *
     * <p>Returns {@code null} when chrome essentials are missing or the canonical URL doesn't match
     * either episode or film grammars (drift signal observed).
     */
    @Nullable
    public JutsuPageMeta parse(String html, @Nullable String expectedUrl) {
        if (html == null || html.isBlank()) {
            ctx.observe(JutsuDriftSignal.EMPTY_RESPONSE, "episode page is empty/null");
            return null;
        }
        Document doc = Jsoup.parse(html);

        Element titleEl = ctx.requireSelector(doc, TITLE_SELECTOR, "episode page missing <title>");
        if (titleEl == null) return null;
        String pageTitle = titleEl.text().trim();
        if (pageTitle.isEmpty()) {
            ctx.observe(JutsuDriftSignal.SCHEMA_VIOLATION, "<title> on episode page is empty");
            return null;
        }

        Element canonicalEl =
                ctx.requireSelector(
                        doc, CANONICAL_SELECTOR, "episode page missing <link rel='canonical'>");
        if (canonicalEl == null) return null;
        String canonical = canonicalEl.attr("href").trim();
        if (canonical.isEmpty()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION, "canonical link on episode page is empty");
            return null;
        }

        Matcher episodeMatch = EPISODE_URL_PATTERN.matcher(canonical);
        Matcher filmMatch = FILM_URL_PATTERN.matcher(canonical);
        boolean isEpisode = episodeMatch.find();
        boolean isFilm = !isEpisode && filmMatch.find();
        if (!isEpisode && !isFilm) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "canonical URL doesn't match episode or film pattern: " + canonical);
            return null;
        }

        Element h1 = ctx.requireSelector(doc, H1_SELECTOR, "episode page missing <h1>");
        if (h1 == null) return null;
        String displayTitle = h1.text().trim();
        if (displayTitle.isEmpty()) {
            ctx.observe(JutsuDriftSignal.SCHEMA_VIOLATION, "<h1> on episode page is empty");
            return null;
        }

        String thumbnailUrl = null;
        Element ogImage = doc.selectFirst(OG_IMAGE_SELECTOR);
        if (ogImage != null) {
            String content = ogImage.attr("content").trim();
            if (!content.isEmpty()) thumbnailUrl = content;
        }

        Element nextArrow = doc.selectFirst(NEXT_EPISODE_SELECTOR);
        String nextUrl = nextArrow == null ? null : trimToNull(nextArrow.attr("href"));
        Element prevArrow = doc.selectFirst(PREV_EPISODE_SELECTOR);
        String prevUrl = prevArrow == null ? null : trimToNull(prevArrow.attr("href"));
        Element allLink = doc.selectFirst(ALL_EPISODES_SELECTOR);
        String allUrl = allLink == null ? null : trimToNull(allLink.attr("href"));

        boolean gated = doc.selectFirst(PAYWALL_SELECTOR) != null;

        if (isEpisode) {
            String slug = episodeMatch.group(1).toLowerCase(Locale.ROOT);
            int season =
                    episodeMatch.group(2) == null ? 1 : Integer.parseInt(episodeMatch.group(2));
            int episode = Integer.parseInt(episodeMatch.group(3));
            crossCheckEpisode(expectedUrl, slug, season, episode, canonical);
            return new JutsuEpisodeMeta(
                    slug,
                    season,
                    episode,
                    displayTitle,
                    pageTitle,
                    canonical,
                    thumbnailUrl,
                    prevUrl,
                    nextUrl,
                    allUrl,
                    gated);
        }

        String slug = filmMatch.group(1).toLowerCase(Locale.ROOT);
        int filmIndex = Integer.parseInt(filmMatch.group(2));
        crossCheckFilm(expectedUrl, slug, filmIndex, canonical);
        return new JutsuFilmMeta(
                slug,
                filmIndex,
                displayTitle,
                pageTitle,
                canonical,
                thumbnailUrl,
                prevUrl,
                nextUrl,
                allUrl,
                gated);
    }

    private void crossCheckEpisode(
            @Nullable String expectedUrl, String slug, int season, int episode, String canonical) {
        if (expectedUrl == null || expectedUrl.isBlank()) return;
        Matcher em = EPISODE_URL_PATTERN.matcher(expectedUrl);
        if (em.find()) {
            String expSlug = em.group(1).toLowerCase(Locale.ROOT);
            int expSeason = em.group(2) == null ? 1 : Integer.parseInt(em.group(2));
            int expEpisode = Integer.parseInt(em.group(3));
            if (!expSlug.equals(slug) || expSeason != season || expEpisode != episode) {
                ctx.observe(
                        JutsuDriftSignal.SCHEMA_VIOLATION,
                        "expected episode "
                                + expectedUrl
                                + " but canonical resolved to "
                                + canonical);
            }
            return;
        }
        // Caller asked for a film-shaped URL but jut.su redirected us to an episode (or vice
        // versa). That's worth observing — silent kind-flip would otherwise mislead consumers.
        Matcher fm = FILM_URL_PATTERN.matcher(expectedUrl);
        if (fm.find()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "expected film "
                            + expectedUrl
                            + " but canonical resolved to episode "
                            + canonical);
        }
    }

    private void crossCheckFilm(
            @Nullable String expectedUrl, String slug, int filmIndex, String canonical) {
        if (expectedUrl == null || expectedUrl.isBlank()) return;
        Matcher fm = FILM_URL_PATTERN.matcher(expectedUrl);
        if (fm.find()) {
            String expSlug = fm.group(1).toLowerCase(Locale.ROOT);
            int expFilm = Integer.parseInt(fm.group(2));
            if (!expSlug.equals(slug) || expFilm != filmIndex) {
                ctx.observe(
                        JutsuDriftSignal.SCHEMA_VIOLATION,
                        "expected film " + expectedUrl + " but canonical resolved to " + canonical);
            }
            return;
        }
        Matcher em = EPISODE_URL_PATTERN.matcher(expectedUrl);
        if (em.find()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "expected episode "
                            + expectedUrl
                            + " but canonical resolved to film "
                            + canonical);
        }
    }

    @Nullable
    private static String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
