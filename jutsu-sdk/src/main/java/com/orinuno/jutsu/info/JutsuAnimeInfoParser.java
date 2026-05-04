package com.orinuno.jutsu.info;

import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Parses {@code GET /{slug}/} (the anime info page) into a {@link JutsuAnimeInfo}.
 *
 * <p>The page is the public-facing landing page for one anime: title, synopsis, season-grouped
 * episode list, genre/type metadata, og:image. The parser uses jsoup for selectors and reports
 * drift signals through {@link JutsuParserContext}.
 *
 * <p>Single-season anime have a single flat episode list under the page's {@code <h1>}; multi-
 * season anime intersperse {@code <h2>N сезон</h2>} headings between episode groups. The parser
 * walks the {@code .short-btn.green.video} anchor list and groups episodes by URL pattern ({@code
 * /{slug}/season-N/episode-M.html} vs {@code /{slug}/episode-M.html}).
 */
public final class JutsuAnimeInfoParser {

    /**
     * Selector for episode anchors. jut.su uses two colour variants:
     *
     * <ul>
     *   <li>{@code short-btn green video} — episode is available to watch.
     *   <li>{@code short-btn black video} — episode is gated (premium-only / not yet aired). Still
     *       a legitimate listing on the season page; we keep it in the listing so callers can
     *       differentiate.
     * </ul>
     *
     * The {@code the_hildi} class appears on both variants for the actual season grids and is the
     * cleanest discriminator from cross-promo blocks. We require it to avoid pulling in
     * navigation/CTA buttons that share {@code short-btn video} but not {@code the_hildi}.
     */
    static final String EPISODE_LINK_SELECTOR = "a.short-btn.video.the_hildi";

    static final String OG_IMAGE_SELECTOR = "meta[property='og:image']";

    static final String YANDEX_THUMBNAIL_SELECTOR = "meta[property='yandex_recommendations_image']";

    static final String META_DESCRIPTION_SELECTOR = "meta[name='description']";

    private static final Pattern URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/(?:season-(\\d+)/)?episode-(\\d+)\\.html");

    private static final Pattern PARENTHESISED_ROMAJI = Pattern.compile("\\(([^()]{2,80})\\)");

    private final JutsuParserContext ctx;

    public JutsuAnimeInfoParser(JutsuParserContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx must not be null");
        this.ctx = ctx;
    }

    /**
     * Parse the response body into an info record. {@code slug} is required because the URL itself
     * is not embedded in the body; the SDK passes it from the request side.
     */
    @Nullable
    public JutsuAnimeInfo parse(String html, String slug) {
        if (html == null || html.isBlank()) {
            ctx.observe(JutsuDriftSignal.EMPTY_RESPONSE, "anime info page is empty/null");
            return null;
        }
        if (slug == null || slug.isBlank()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION, "anime info parse called with blank slug");
            return null;
        }
        Document doc = Jsoup.parse(html);

        Element h1 = ctx.requireSelector(doc, "h1", "anime info page missing <h1>");
        if (h1 == null) return null;
        String title = h1.text().trim();
        if (title.isEmpty()) {
            ctx.observe(JutsuDriftSignal.SCHEMA_VIOLATION, "<h1> on info page is empty");
            return null;
        }

        String originalTitle = extractOriginalTitle(doc);
        String synopsis = extractSynopsis(doc);
        String thumbnail = extractThumbnail(doc);
        Optional<JutsuYear> year = extractYear(doc);
        Set<JutsuGenre> genres = EnumSet.noneOf(JutsuGenre.class);
        Set<JutsuType> types = EnumSet.noneOf(JutsuType.class);
        extractCategoriesFromBody(doc, genres, types);
        List<JutsuSeason> seasons = extractSeasons(doc, slug);

        return new JutsuAnimeInfo(
                slug, title, originalTitle, synopsis, year, genres, types, thumbnail, seasons);
    }

    @Nullable
    private static String extractOriginalTitle(Document doc) {
        Element meta = doc.selectFirst(META_DESCRIPTION_SELECTOR);
        if (meta == null) return null;
        String content = meta.attr("content");
        if (content.isBlank()) return null;
        Matcher m = PARENTHESISED_ROMAJI.matcher(content);
        if (m.find()) return m.group(1).trim();
        return null;
    }

    @Nullable
    private static String extractSynopsis(Document doc) {
        // jut.su's info pages don't ship a structured synopsis field — they wrap loose copy in
        // <p> blocks under .b-b-text or in the article body. Use a best-effort heuristic: take
        // the first non-trivial paragraph whose text is at least 80 chars.
        Elements paragraphs = doc.select("p, div.b-b-text, div.description");
        for (Element p : paragraphs) {
            String text = p.text().trim();
            if (text.length() >= 80) return text;
        }
        return null;
    }

    @Nullable
    private static String extractThumbnail(Document doc) {
        Element ogImage = doc.selectFirst(OG_IMAGE_SELECTOR);
        if (ogImage != null) {
            String content = ogImage.attr("content").trim();
            if (!content.isEmpty()) return content;
        }
        Element yandexImage = doc.selectFirst(YANDEX_THUMBNAIL_SELECTOR);
        if (yandexImage != null) {
            String content = yandexImage.attr("content").trim();
            if (!content.isEmpty()) return content;
        }
        Element posterImg = doc.selectFirst("img.poster");
        if (posterImg != null) {
            String src = posterImg.attr("src").trim();
            if (!src.isEmpty()) return src;
        }
        return null;
    }

    private static Optional<JutsuYear> extractYear(Document doc) {
        // jut.su's info pages don't expose a stable structured year — fall back to the meta
        // description if a 4-digit year appears there.
        Element meta = doc.selectFirst(META_DESCRIPTION_SELECTOR);
        if (meta == null) return Optional.empty();
        String content = meta.attr("content");
        Matcher m = Pattern.compile("\\b(19|20)\\d{2}\\b").matcher(content);
        if (!m.find()) return Optional.empty();
        try {
            int year = Integer.parseInt(m.group());
            return com.orinuno.jutsu.catalog.JutsuCatalogParser.mapGranularYear(
                    String.valueOf(year));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    /**
     * Mine the page body for genre/type signals. Info pages link out to {@code /anime/{slug}/} URLs
     * from the genre block, so we follow those links rather than rely on top-level CSS classes (the
     * body doesn't reliably carry {@code anime_ganre_*} classes).
     */
    private void extractCategoriesFromBody(
            Document doc, Set<JutsuGenre> genres, Set<JutsuType> types) {
        Elements links = doc.select("a[href^='/anime/']");
        for (Element link : links) {
            String href = link.attr("href");
            String slugPart = href.replaceFirst("^/anime/", "").replaceAll("/$", "");
            if (slugPart.contains("/")) continue;
            // Skip empty (the bare /anime/ link) and the order-by-* sort links.
            if (slugPart.isEmpty()) continue;
            if (slugPart.startsWith("order-by-")) continue;
            JutsuGenre.fromSlug(slugPart).ifPresent(genres::add);
            JutsuType.fromSlug(slugPart).ifPresent(types::add);
        }
    }

    private List<JutsuSeason> extractSeasons(Document doc, String slug) {
        Elements anchors = doc.select(EPISODE_LINK_SELECTOR);
        if (anchors.isEmpty()) {
            // Anonymous viewer with premium-only series: no playable links visible. Not drift —
            // the page rendered fine, episodes are just gated.
            return List.of();
        }
        // Group by season; preserve the order in which season indices first appear so single-
        // season anime keep their natural ordering.
        Map<Integer, JutsuSeason.Builder> seasonBuilders = new TreeMap<>();
        for (Element a : anchors) {
            String url = a.attr("href").trim();
            JutsuEpisodeListing listing = parseEpisodeAnchor(url, a.text(), slug);
            if (listing == null) continue;
            seasonBuilders
                    .computeIfAbsent(
                            listing.season(),
                            idx -> new JutsuSeason.Builder(idx, defaultSeasonName(idx)))
                    .add(listing);
        }
        List<JutsuSeason> result = new ArrayList<>(seasonBuilders.size());
        for (Map.Entry<Integer, JutsuSeason.Builder> entry : seasonBuilders.entrySet()) {
            // Resolve the season name: prefer the captured h2 text when available.
            String configured = configuredSeasonName(doc, entry.getKey());
            JutsuSeason.Builder b = entry.getValue();
            if (configured != null) b.name(configured);
            result.add(b.build());
        }
        return result;
    }

    @Nullable
    private static String configuredSeasonName(Document doc, int seasonIndex) {
        // Look for an <h2> whose text starts with the season index. Tolerant of "1 сезон",
        // "1-й сезон", "First season", etc.
        for (Element h2 : doc.select("h2")) {
            String text = h2.text().trim();
            if (text.matches("(?i)\\s*" + seasonIndex + "(?:\\s|-).*")) {
                return text;
            }
        }
        return null;
    }

    private static String defaultSeasonName(int index) {
        return index + " сезон";
    }

    @Nullable
    private JutsuEpisodeListing parseEpisodeAnchor(String url, String label, String expectedSlug) {
        if (url == null || url.isBlank()) return null;
        Matcher m = URL_PATTERN.matcher(url);
        if (!m.find()) {
            ctx.observe(
                    JutsuDriftSignal.SCHEMA_VIOLATION,
                    "anime info episode anchor href doesn't match pattern: " + url);
            return null;
        }
        String slug = m.group(1).toLowerCase(Locale.ROOT);
        int season = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
        int episode = Integer.parseInt(m.group(3));
        if (!expectedSlug.equalsIgnoreCase(slug)) {
            // jut.su's anime info pages occasionally cross-link to "related anime" episodes; the
            // parser drops those silently rather than mixing them into the current anime's
            // listing. NOT drift — it's expected behaviour on cross-promo blocks.
            return null;
        }
        return new JutsuEpisodeListing(slug, season, episode, label, url);
    }
}
