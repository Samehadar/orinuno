package com.orinuno.jutsu.catalog;

import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * Parses the AJAX catalog response (POST /anime/{path}/ with {@code ajax_load=yes}) into a {@link
 * JutsuCatalogPage}. The response shape is a flat sequence of {@code <div class="all_anime_global
 * ...">} cards plus a JS prelude line {@code var anime_page_next = true|false;}.
 *
 * <p>The parser is drift-aware: every selector hit goes through {@link JutsuParserContext}, every
 * unknown class on a card is reported as {@link JutsuDriftSignal#NEW_CSS_CLASS}, and the {@code
 * anime_page_next} marker absence is observed as {@link JutsuDriftSignal#SCHEMA_VIOLATION}.
 *
 * <p>An empty body (zero {@code all_anime_global} cards) is a legitimate steady state for "no
 * matches found" — we don't observe drift on it. The caller (filter→empty fixture, search→no match)
 * is responsible for deciding what to do.
 */
public final class JutsuCatalogParser {

    static final String CARD_SELECTOR = "div.all_anime_global";

    private static final Pattern PAGE_NEXT =
            Pattern.compile("var\\s+anime_page_next\\s*=\\s*(true|false)\\s*;");

    private static final Pattern THUMB_URL =
            Pattern.compile("background:\\s*url\\(\\s*['\"]?(.*?)['\"]?\\s*\\)");

    private static final Pattern AAILINES_EPISODES =
            Pattern.compile("(\\d+)\\s*сер", Pattern.CASE_INSENSITIVE);

    private static final Pattern AAILINES_MOVIES =
            Pattern.compile("(\\d+)\\s*фильм", Pattern.CASE_INSENSITIVE);

    private static final Pattern ID_FROM_ANIME_FS = Pattern.compile("anime_fs_(\\d+)");

    private static final String GENRE_CLASS_PREFIX = "anime_ganre_";
    private static final String TYPE_CLASS_PREFIX = "anime_type_";
    private static final String YEAR_CLASS_PREFIX = "anime_year_";

    private final JutsuParserContext ctx;

    public JutsuCatalogParser(JutsuParserContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx must not be null");
        this.ctx = ctx;
    }

    /**
     * Parse a catalog response body into a page.
     *
     * @param html the raw response body (already decoded to a String — see {@link
     *     com.orinuno.jutsu.parser.JutsuHtmlCharset})
     * @param page the 1-based page number this response corresponds to (used to populate {@link
     *     JutsuCatalogPage#page()})
     */
    public JutsuCatalogPage parse(String html, int page) {
        if (html == null || html.isBlank()) {
            ctx.observe(JutsuDriftSignal.EMPTY_RESPONSE, "catalog response is empty/null");
            return JutsuCatalogPage.empty(page);
        }
        Document doc = Jsoup.parse(html);
        Elements cards = doc.select(CARD_SELECTOR);
        boolean hasMore = extractPageNextFlag(html);
        if (cards.isEmpty()) {
            // Zero cards is a valid "no match" state for search/filter. We do NOT observe drift
            // here; the caller knows whether they expected hits.
            return new JutsuCatalogPage(List.of(), page, hasMore);
        }
        List<JutsuCatalogEntry> entries = new ArrayList<>(cards.size());
        for (Element card : cards) {
            JutsuCatalogEntry entry = parseCard(card);
            if (entry != null) entries.add(entry);
        }
        return new JutsuCatalogPage(entries, page, hasMore);
    }

    private boolean extractPageNextFlag(String html) {
        Matcher m = PAGE_NEXT.matcher(html);
        if (!m.find()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "catalog response missing 'var anime_page_next' JS marker");
            return false;
        }
        return Boolean.parseBoolean(m.group(1));
    }

    @Nullable
    private JutsuCatalogEntry parseCard(Element card) {
        // Required: the URL slug (carries the entire identity of this entry).
        Element link = ctx.requireSelector(card, "a[href]", "catalog card has no <a href>");
        if (link == null) return null;
        String href = link.attr("href");
        String slug = extractSlug(href);
        if (slug.isEmpty()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "catalog card has empty slug from href '" + href + "'");
            return null;
        }

        // Required: the Russian title (.aaname). Drift if missing.
        Element titleEl = ctx.requireSelector(card, "div.aaname", "catalog card missing .aaname");
        if (titleEl == null) return null;
        String title = titleEl.text().trim();
        if (title.isEmpty()) {
            ctx.observe(JutsuDriftSignal.SCHEMA_VIOLATION, "catalog card has blank .aaname");
            return null;
        }

        // Optional fields.
        int siteId = extractSiteId(card.attr("id"));
        String thumbnail = extractThumbnail(card);
        Integer episodeCount = extractAailinesNumber(card, AAILINES_EPISODES);
        Integer movieCount = extractAailinesNumber(card, AAILINES_MOVIES);
        String originalTitle = extractOriginalTitle(card);

        Set<JutsuGenre> genres = EnumSet.noneOf(JutsuGenre.class);
        Set<JutsuType> types = EnumSet.noneOf(JutsuType.class);
        Optional<JutsuYear> year = Optional.empty();
        for (String klass : card.classNames()) {
            if (klass.startsWith(GENRE_CLASS_PREFIX)) {
                String slugPart = klass.substring(GENRE_CLASS_PREFIX.length());
                Optional<JutsuGenre> hit = JutsuGenre.fromSlug(slugPart);
                if (hit.isPresent()) genres.add(hit.get());
                else {
                    // Could be a type misclassified by the form (form uses anime_ganre_* for
                    // both genres and types via id, but cards use anime_type_* for types — so
                    // this branch should never hit. If it does, the upstream changed.)
                    if (JutsuType.fromSlug(slugPart).isEmpty()) {
                        ctx.observe(
                                JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                                "card class '" + klass + "' did not match any genre or type enum");
                    }
                }
            } else if (klass.startsWith(TYPE_CLASS_PREFIX)) {
                String slugPart = klass.substring(TYPE_CLASS_PREFIX.length());
                Optional<JutsuType> hit = JutsuType.fromSlug(slugPart);
                if (hit.isPresent()) types.add(hit.get());
                else {
                    ctx.observe(
                            JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                            "card class '" + klass + "' did not match any type enum");
                }
            } else if (klass.startsWith(YEAR_CLASS_PREFIX)) {
                String slugPart = klass.substring(YEAR_CLASS_PREFIX.length());
                Optional<JutsuYear> bucketHit = JutsuYear.fromSlug(slugPart);
                if (bucketHit.isPresent()) {
                    year = bucketHit;
                    continue;
                }
                // Card classes also include granular per-year tokens like "anime_year_2018"
                // alongside the bucket. Map any 4-digit year to its bucket so the entry's
                // .year() field stays populated without spamming drift events.
                Optional<JutsuYear> bucketFromInt = mapGranularYear(slugPart);
                if (bucketFromInt.isPresent()) {
                    if (year.isEmpty()) year = bucketFromInt;
                    continue;
                }
                ctx.observe(
                        JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                        "card class '" + klass + "' did not match any year enum or bucket");
            }
        }

        return new JutsuCatalogEntry(
                siteId,
                slug,
                title,
                originalTitle,
                thumbnail,
                episodeCount,
                movieCount,
                genres,
                types,
                year);
    }

    /**
     * Map a granular 4-digit year (as it appears on entry cards, e.g., {@code anime_year_2018}) to
     * the bucket {@link JutsuYear} that the form filter uses. Returns empty when the slug is not a
     * 4-digit year or falls outside the supported range.
     *
     * <p>Public for cross-package reuse (e.g. by {@code JutsuAnimeInfoParser}); see ADR-0015 for
     * why we keep this on the catalog parser instead of {@link JutsuYear} itself.
     */
    public static Optional<JutsuYear> mapGranularYear(String slugPart) {
        if (slugPart == null || slugPart.length() != 4) return Optional.empty();
        int parsed;
        try {
            parsed = Integer.parseInt(slugPart);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (parsed < 1900 || parsed > 2100) return Optional.empty();
        if (parsed >= 2024) {
            // Map 2024+, 2025+, 2026+ to the corresponding individual bucket if it exists.
            for (JutsuYear y : JutsuYear.values()) {
                if (y.slug().equals(String.valueOf(parsed))) return Optional.of(y);
            }
            // Future years (2027+) bucket under ONGOING (the only bucket above the latest fixed
            // year on jut.su's form). Conservative — alternative would be UNKNOWN_FILTER_SLUG.
            return Optional.of(JutsuYear.ONGOING);
        }
        if (parsed >= 2015) return Optional.of(JutsuYear.Y_2015_2023);
        if (parsed >= 2008) return Optional.of(JutsuYear.Y_2008_2014);
        if (parsed >= 2000) return Optional.of(JutsuYear.Y_2000_2007);
        return Optional.of(JutsuYear.BEFORE_2000);
    }

    private static String extractSlug(String href) {
        if (href == null) return "";
        String h = href.trim();
        if (h.startsWith("/")) h = h.substring(1);
        if (h.endsWith("/")) h = h.substring(0, h.length() - 1);
        // /neon-evangelion → neon-evangelion;  /onepuunchman/episode-1.html ignored (only top
        // entries on the catalog page).
        int slash = h.indexOf('/');
        return slash >= 0 ? h.substring(0, slash) : h;
    }

    private static int extractSiteId(@Nullable String idAttr) {
        if (idAttr == null) return -1;
        Matcher m = ID_FROM_ANIME_FS.matcher(idAttr);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    @Nullable
    private static String extractThumbnail(Element card) {
        Element image = card.selectFirst("div.all_anime_image");
        if (image == null) return null;
        String style = image.attr("style");
        Matcher m = THUMB_URL.matcher(style);
        if (m.find()) {
            String url = m.group(1).trim();
            return url.isEmpty() ? null : url;
        }
        return null;
    }

    @Nullable
    private static Integer extractAailinesNumber(Element card, Pattern pattern) {
        Element lines = card.selectFirst("div.aailines");
        if (lines == null) return null;
        // The .aailines block uses <br> as a separator — keep the breaks readable for the regex.
        String html = lines.html().replaceAll("<br\\s*/?>", "\n");
        String text = Jsoup.parse(html).text();
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    @Nullable
    private static String extractOriginalTitle(Element card) {
        Element tooltip = card.selectFirst("div.tooltip_of_the_anime");
        if (tooltip == null) return null;
        String content = tooltip.attr("content");
        if (content.isBlank()) return null;
        // The content attribute holds escaped HTML; parse it as a fragment to walk into the
        // inner anchor.
        Document fragment = Jsoup.parse(content, "", Parser.xmlParser());
        Element first = fragment.selectFirst("a.tooltip_title_in_anime");
        if (first == null) return null;
        // The original title sits between the </a> and the next <span>, separated by <br>. Take
        // the text after the anchor's parent's first text node.
        Element parent = first.parent();
        if (parent == null) return null;
        String all = parent.text();
        String ru = first.text().trim();
        int idx = all.indexOf(ru);
        if (idx < 0 || idx + ru.length() >= all.length()) return null;
        String after = all.substring(idx + ru.length()).trim();
        return after.isEmpty() ? null : after;
    }
}
