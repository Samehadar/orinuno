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
 * Parses {@code GET /{slug}/(season-N/)?episode-M.html} into a {@link JutsuEpisodeMeta}.
 *
 * <p>The episode page is a fully rendered viewer page; this parser extracts the cheap chrome
 * (title, thumbnail, prev/next arrows, paywall flag) without invoking the heavier video-decoder
 * pipeline. The decoder regex-based path in {@code JutsuDecoder} remains the single source of truth
 * for actual video URL extraction.
 */
public final class JutsuEpisodePageParser {

    /** Episode URL grammar — same shape as {@code JutsuAnimeInfoParser}. */
    private static final Pattern URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/(?:season-(\\d+)/)?episode-(\\d+)\\.html");

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
     * slug/season/episode would mean jut.su silently redirected us to a different page). When
     * {@code expectedUrl} is {@code null}, no cross-check is performed.
     */
    @Nullable
    public JutsuEpisodeMeta parse(String html, @Nullable String expectedUrl) {
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

        Matcher m = URL_PATTERN.matcher(canonical);
        if (!m.find()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "canonical URL doesn't match episode pattern: " + canonical);
            return null;
        }
        String slug = m.group(1).toLowerCase(Locale.ROOT);
        int season = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int episode = Integer.parseInt(m.group(3));

        if (expectedUrl != null && !expectedUrl.isBlank()) {
            Matcher em = URL_PATTERN.matcher(expectedUrl);
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
            }
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

    @Nullable
    private static String trimToNull(@Nullable String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
