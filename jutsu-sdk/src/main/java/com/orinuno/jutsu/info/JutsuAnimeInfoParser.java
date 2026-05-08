package com.orinuno.jutsu.info;

import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
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

    /**
     * Selector for the labelled info block ("Жанры: …<br>
     * Темы: …<br>
     * …<br>
     * Возрастной рейтинг: …"). One block per page; jut.su renders the same chrome around every
     * anime on every season variant.
     */
    static final String INFO_BLOCK_SELECTOR = "div.under_video_additional";

    /** Class fragment carried by the age-rating badge ({@code age_rating_18} → {@code 18}). */
    private static final Pattern AGE_RATING_CLASS = Pattern.compile("age_rating_(\\d+)");

    /**
     * Labels jut.su prints before each row inside the {@code under_video_additional} block. We
     * match by literal Cyrillic prefix because there's no stable per-row CSS class to anchor on.
     * Order is irrelevant for matching itself but mirrors the upstream template for readability.
     */
    private static final String LABEL_GENRES = "Жанры:";

    private static final String LABEL_TYPES = "Темы:";
    private static final String LABEL_YEARS = "Годы выпуска:";
    private static final String LABEL_ORIGINAL = "Оригинальное название:";
    private static final String LABEL_AGE_RATING = "Возрастной рейтинг:";

    private static final Pattern URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/(?:season-(\\d+)/)?episode-(\\d+)\\.html");

    private static final Pattern PARENTHESISED_ROMAJI = Pattern.compile("\\(([^()]{2,80})\\)");

    private static final Pattern FOUR_DIGIT_YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");

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

        InfoBlock infoBlock = extractInfoBlock(doc);
        Set<JutsuGenre> genres = EnumSet.noneOf(JutsuGenre.class);
        Set<JutsuType> types = EnumSet.noneOf(JutsuType.class);
        genres.addAll(infoBlock.genres);
        types.addAll(infoBlock.types);
        // Drift fallback: when the labelled block disappears or genre/type slugs there don't
        // resolve, sweep the page body for /anime/{slug}/ links the way the parser used to. Keeps
        // the response viable through low-grade chrome rewrites; the labelled block is preferred
        // because it disambiguates from the sidebar / cross-promo blocks.
        if (genres.isEmpty() && types.isEmpty()) {
            extractCategoriesFromBody(doc, genres, types);
        }

        List<JutsuSeason> seasons = extractSeasons(doc, slug);

        return new JutsuAnimeInfo(
                slug,
                title,
                originalTitle,
                synopsis,
                year,
                infoBlock.years,
                infoBlock.ageRating,
                genres,
                types,
                thumbnail,
                seasons);
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
     * Pull the labelled info block ("Жанры: …<br>
     * Темы: …<br>
     * Годы выпуска: …<br>
     * Оригинальное название: …<br>
     * Возрастной рейтинг: …") out of the page. This is the authoritative source for genre / type /
     * years-of-release / age-rating chrome on jut.su info pages — much narrower than the page-wide
     * {@code /anime/{slug}/} sweep that used to power genre/type extraction, which over-matched on
     * sidebar / cross-promo blocks.
     *
     * <p>Implementation walks the block's HTML split by {@code <br>} into segments, then matches
     * each segment against its leading label ({@code "Жанры:"}, {@code "Темы:"}, {@code "Годы
     * выпуска:"}, {@code "Возрастной рейтинг:"}). Order inside the block is fixed by jut.su's
     * template; we don't rely on it explicitly so a future template tweak doesn't take us down.
     *
     * <p>Drift handling: missing block surfaces a {@link JutsuDriftSignal#SELECTOR_MISS} and
     * returns an empty {@link InfoBlock} (chrome stays unset, parse continues). Caller falls back
     * to the legacy page-wide sweep for genres / types if both come back empty.
     */
    private InfoBlock extractInfoBlock(Document doc) {
        Element block = doc.selectFirst(INFO_BLOCK_SELECTOR);
        if (block == null) {
            ctx.observe(
                    JutsuDriftSignal.SELECTOR_MISS,
                    "anime info labelled block (" + INFO_BLOCK_SELECTOR + ") not found");
            return InfoBlock.empty();
        }

        Set<JutsuGenre> genres = EnumSet.noneOf(JutsuGenre.class);
        Set<JutsuType> types = EnumSet.noneOf(JutsuType.class);
        LinkedHashSet<Integer> years = new LinkedHashSet<>();
        Optional<JutsuAgeRating> ageRating = Optional.empty();

        // Split the block's HTML on <br> and re-parse each segment in isolation. jsoup's text
        // walker collapses <br> into whitespace, which would lose the boundary; HTML splitting
        // preserves it explicitly. We re-parse each segment via parseBodyFragment(...) and use
        // the returned body() as the working root. We deliberately do NOT wrap the segment in a
        // synthetic tag (e.g. <root>) — jsoup's HTML5 tokeniser treats unknown tags as void in
        // some edge cases, and on the live jut.su HTML this manifested as the first segment
        // (the one starting with leading "\r\n\r\n" before "Жанры:") losing its content while
        // later segments parsed correctly. parseBodyFragment(...).body() is the documented
        // jsoup primitive for "give me an element wrapper around an HTML fragment".
        String[] segments = block.html().split("(?i)<br\\s*/?>");
        for (String raw : segments) {
            String segHtml = raw.trim();
            if (segHtml.isEmpty()) continue;
            Element seg = Jsoup.parseBodyFragment(segHtml).body();
            if (seg == null) continue;
            String text = seg.text();
            // jut.su renders an anonymous-viewer "Добавить в раздел «На очереди»" CTA inline,
            // without a <br>, so the first segment frequently starts with that button text
            // followed by the actual "Жанры:" row. We classify the segment by the FIRST known
            // label substring we find anywhere in it (not just at offset 0) so that pre-label
            // chrome doesn't hide the row from us. Anchor filtering by href^='/anime/' below
            // already keeps the CTA's own anchors out of the slug lists.
            String label = firstLabel(text);
            if (label == null) continue;
            switch (label) {
                case LABEL_GENRES ->
                        collectAnimeSlugs(seg)
                                .forEach(s -> JutsuGenre.fromSlug(s).ifPresent(genres::add));
                case LABEL_TYPES ->
                        collectAnimeSlugs(seg)
                                .forEach(s -> JutsuType.fromSlug(s).ifPresent(types::add));
                case LABEL_YEARS -> {
                    for (Element a : seg.select("a[href^='/anime/']")) {
                        Matcher m = FOUR_DIGIT_YEAR.matcher(a.text());
                        if (m.find()) {
                            try {
                                years.add(Integer.parseInt(m.group()));
                            } catch (NumberFormatException ignore) {
                                // Defensive: regex matches only digits, never throws.
                            }
                        }
                    }
                }
                case LABEL_AGE_RATING -> {
                    Element span = seg.selectFirst("span[class*=age_rating_]");
                    if (span != null) {
                        Matcher m = AGE_RATING_CLASS.matcher(span.className());
                        while (m.find()) {
                            try {
                                int age = Integer.parseInt(m.group(1));
                                Optional<JutsuAgeRating> resolved = JutsuAgeRating.fromAge(age);
                                if (resolved.isPresent()) {
                                    ageRating = resolved;
                                    break;
                                }
                            } catch (NumberFormatException ignore) {
                                // Class regex captured digits, never throws.
                            }
                        }
                    }
                }
                case LABEL_ORIGINAL -> {
                    // We already have originalTitle from the meta description; the labelled-block
                    // version is redundant. Match it explicitly anyway so the segment is "owned"
                    // by ORIGINAL classification and doesn't accidentally fall through to the
                    // anchor-based clauses above.
                }
                default -> {
                    // Future-proofing: an unknown label key from firstLabel(...) would be a code
                    // bug — the constant table is closed. Ignore silently rather than throwing.
                }
            }
        }

        return new InfoBlock(genres, types, List.copyOf(years), ageRating);
    }

    /**
     * Identify which labelled row a segment carries by scanning for the earliest known label
     * substring. Returns {@code null} when none of the labels show up — caller skips the segment.
     *
     * <p>"Earliest" is defined by character offset, so a segment that starts with chrome (a CTA
     * button, an inline ad, etc.) followed by a known label still classifies as that label.
     * Tie-breaking is unnecessary because two labels never co-exist in a single {@code
     * <br>}-separated row in the upstream template.
     */
    @Nullable
    private static String firstLabel(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] all = {LABEL_GENRES, LABEL_TYPES, LABEL_YEARS, LABEL_ORIGINAL, LABEL_AGE_RATING};
        int bestPos = Integer.MAX_VALUE;
        String best = null;
        for (String l : all) {
            int p = text.indexOf(l);
            if (p >= 0 && p < bestPos) {
                bestPos = p;
                best = l;
            }
        }
        return best;
    }

    /**
     * Helper for {@link #extractInfoBlock(Document)} — pull each {@code <a href="/anime/SLUG/">}
     * descendant's slug as a normalised lowercase string. Anchors that don't point at a single
     * filter slug (nested URLs like {@code /anime/order-by-name/}) are dropped silently.
     */
    private static List<String> collectAnimeSlugs(Element seg) {
        List<String> out = new ArrayList<>();
        for (Element a : seg.select("a[href^='/anime/']")) {
            String href = a.attr("href");
            String slugPart = href.replaceFirst("^/anime/", "").replaceAll("/$", "");
            if (slugPart.isEmpty() || slugPart.contains("/")) continue;
            if (slugPart.startsWith("order-by-")) continue;
            out.add(slugPart.toLowerCase(Locale.ROOT));
        }
        return out;
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

    /**
     * Plain holder for the labelled-info-block extraction result. Kept package-private so the
     * record stays an implementation detail of the parser.
     */
    private record InfoBlock(
            Set<JutsuGenre> genres,
            Set<JutsuType> types,
            List<Integer> years,
            Optional<JutsuAgeRating> ageRating) {

        static InfoBlock empty() {
            return new InfoBlock(
                    EnumSet.noneOf(JutsuGenre.class),
                    EnumSet.noneOf(JutsuType.class),
                    List.of(),
                    Optional.empty());
        }
    }
}
