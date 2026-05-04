package com.orinuno.jutsu.filter;

import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Parses the {@code <div class="anime_choose_wall">} block on the live {@code GET /anime/} page and
 * emits the {@code (slug, label)} tuples for each filter category. Used by:
 *
 * <ol>
 *   <li>The drift baseline test {@code JutsuFilterEnumDriftTest}: live form ⊇ enum constants.
 *   <li>The orinuno-app scheduled canary probe: refresh of the (slug, label) mapping every 6h.
 * </ol>
 *
 * <p>This parser only <em>reads</em>; the {@link com.orinuno.jutsu.filter.JutsuGenre}, {@link
 * JutsuType}, {@link JutsuYear}, {@link JutsuSort} enums remain the source of truth at compile
 * time. Anything new the parser sees is reported via {@link JutsuDriftSignal#UNKNOWN_FILTER_SLUG}
 * so operators get a notification before silent gaps appear in catalog filtering.
 */
public final class JutsuFilterFormParser {

    /** Block selectors taken from the captured form fixture (anime_filter_form.html). */
    static final String GENRE_BLOCK = "div.anime_choose_block_ganres";

    static final String TYPE_BLOCK = "div.anime_choose_block_types";

    static final String YEAR_BLOCK = "div.anime_choose_block_years";

    static final String ORDER_BLOCK = "div.anime_choose_block_order";

    /** Each radio button is an {@code <a href="/anime/{slug}/">label</a>} inside its block. */
    static final String LINK_SELECTOR = "div.anime_choose_radio_line a[href]";

    private final JutsuParserContext ctx;

    public JutsuFilterFormParser(JutsuParserContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx must not be null");
        this.ctx = ctx;
    }

    /** Parse the full {@code GET /anime/} HTML body and return the four ordered entry lists. */
    public JutsuFilterFormSnapshot parse(String html) {
        if (html == null || html.isBlank()) {
            ctx.observe(JutsuDriftSignal.EMPTY_RESPONSE, "filter form HTML is empty/null");
            return JutsuFilterFormSnapshot.empty();
        }
        Document doc = Jsoup.parse(html);

        Element genreBlock = ctx.requireSelector(doc, GENRE_BLOCK, "filter form genre block");
        Element typeBlock = ctx.requireSelector(doc, TYPE_BLOCK, "filter form type block");
        Element yearBlock = ctx.requireSelector(doc, YEAR_BLOCK, "filter form year block");
        Element orderBlock = ctx.requireSelector(doc, ORDER_BLOCK, "filter form order block");

        List<FilterEntry> genres = extractEntries(genreBlock, "genre");
        List<FilterEntry> types = extractEntries(typeBlock, "type");
        List<FilterEntry> years = extractEntries(yearBlock, "year");
        List<FilterEntry> orders = extractEntries(orderBlock, "order");

        // Genres / types / years use the URL slug (the last path segment of /anime/{slug}/).
        // Order uses the FULL trailing segment because BY_RATING uses href="/anime/" with no slug.
        validateGenres(genres);
        validateTypes(types);
        validateYears(years);
        validateOrders(orders);

        return new JutsuFilterFormSnapshot(genres, types, years, orders);
    }

    private List<FilterEntry> extractEntries(Element block, String category) {
        if (block == null) return List.of();
        List<FilterEntry> result = new ArrayList<>();
        Elements links = block.select(LINK_SELECTOR);
        if (links.isEmpty()) {
            ctx.observe(
                    JutsuDriftSignal.SELECTOR_MISS,
                    "no radio-line links inside " + category + " block",
                    LINK_SELECTOR);
        }
        for (Element link : links) {
            String href = link.attr("href").trim();
            String label = link.text().trim();
            String slug = extractSlug(href);
            result.add(new FilterEntry(slug, label, href));
        }
        return List.copyOf(result);
    }

    private static String extractSlug(String href) {
        // /anime/foo/  ->  foo
        // /anime/      ->  "" (default sort)
        if (href == null) return "";
        String trimmed = href.trim();
        if (!trimmed.startsWith("/anime/")) {
            return trimmed;
        }
        String tail = trimmed.substring("/anime/".length());
        if (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
        return tail;
    }

    private void validateGenres(List<FilterEntry> entries) {
        for (FilterEntry entry : entries) {
            if (JutsuGenre.fromSlug(entry.slug()).isEmpty()) {
                ctx.observe(
                        JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                        "new genre slug '" + entry.slug() + "' (label='" + entry.label() + "')");
            }
        }
    }

    private void validateTypes(List<FilterEntry> entries) {
        for (FilterEntry entry : entries) {
            if (JutsuType.fromSlug(entry.slug()).isEmpty()) {
                ctx.observe(
                        JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                        "new type slug '" + entry.slug() + "' (label='" + entry.label() + "')");
            }
        }
    }

    private void validateYears(List<FilterEntry> entries) {
        for (FilterEntry entry : entries) {
            if (JutsuYear.fromSlug(entry.slug()).isEmpty()) {
                ctx.observe(
                        JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                        "new year slug '" + entry.slug() + "' (label='" + entry.label() + "')");
            }
        }
    }

    private void validateOrders(List<FilterEntry> entries) {
        for (FilterEntry entry : entries) {
            // The default order has slug="" (href="/anime/") — that's BY_RATING by definition.
            if (entry.slug().isEmpty()) continue;
            if (JutsuSort.fromSlug(entry.slug()).isEmpty()) {
                ctx.observe(
                        JutsuDriftSignal.UNKNOWN_FILTER_SLUG,
                        "new order slug '" + entry.slug() + "' (label='" + entry.label() + "')");
            }
        }
    }

    /** One row from the filter form: the URL slug + the rendered label + the raw href. */
    public record FilterEntry(String slug, String label, String href) {
        public FilterEntry {
            if (slug == null) throw new IllegalArgumentException("slug must not be null");
            if (label == null) throw new IllegalArgumentException("label must not be null");
            if (href == null) throw new IllegalArgumentException("href must not be null");
        }
    }

    /** Ordered snapshot of the four filter blocks. */
    public record JutsuFilterFormSnapshot(
            List<FilterEntry> genres,
            List<FilterEntry> types,
            List<FilterEntry> years,
            List<FilterEntry> orders) {

        public JutsuFilterFormSnapshot {
            genres = genres == null ? List.of() : List.copyOf(genres);
            types = types == null ? List.of() : List.copyOf(types);
            years = years == null ? List.of() : List.copyOf(years);
            orders = orders == null ? List.of() : List.copyOf(orders);
        }

        public static JutsuFilterFormSnapshot empty() {
            return new JutsuFilterFormSnapshot(List.of(), List.of(), List.of(), List.of());
        }

        /** Convenience: collapses everything into a single keyed map (category → entries). */
        public Map<String, List<FilterEntry>> asMap() {
            Map<String, List<FilterEntry>> map = new LinkedHashMap<>();
            map.put("genres", genres);
            map.put("types", types);
            map.put("years", years);
            map.put("orders", orders);
            return map;
        }
    }
}
