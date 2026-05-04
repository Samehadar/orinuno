package com.orinuno.jutsu.notice;

import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * Parses the response body of {@code POST /engine/ajax/site_notice.php} into a {@link
 * JutsuNoticeFeed}.
 *
 * <p>The response is a bare HTML fragment (no {@code <html>} wrapper) — jut.su's frontend appends
 * it directly into the notice container. We feed it to jsoup as fragment HTML so missing wrapper
 * tags don't trip selector queries.
 */
public final class JutsuNoticeParser {

    static final String NOTICE_BLOCK_SELECTOR = "div.notice_cont";

    static final String NOTICE_IMG_SELECTOR = "a.notice_img";

    static final String NOTICE_THUMBNAIL_SELECTOR = "a.notice_img img";

    static final String NOTICE_TITLE_SELECTOR = "a.notice_title2_2";

    static final String NOTICE_DATE_SELECTOR = "div.notice_date2";

    /** Standard episode URL grammar — same as {@code JutsuEpisodePageParser}. */
    private static final Pattern URL_PATTERN =
            Pattern.compile("/([a-z0-9-]+)/(?:season-(\\d+)/)?episode-(\\d+)\\.html");

    /**
     * Compact variant URL: {@code /{slug}/{seasonOrChapter}/{episode}.html}. jut.su uses this for
     * non-standard release shapes (e.g. Boruto specials at {@code /boruto/113/1.html}). Treated as
     * a known minor variant — no drift signal — so the feed parser doesn't lose 1-2% of entries for
     * what is a legitimate URL shape on the site.
     */
    private static final Pattern URL_PATTERN_VARIANT =
            Pattern.compile("/([a-z0-9-]+)/(\\d+)/(\\d+)\\.html");

    private final JutsuParserContext ctx;

    public JutsuNoticeParser(JutsuParserContext ctx) {
        if (ctx == null) throw new IllegalArgumentException("ctx must not be null");
        this.ctx = ctx;
    }

    /**
     * Parse the response. {@code requestedCursor} is the {@code notice_id} the SDK asked for; it's
     * embedded into the result so callers can compute the next page cursor.
     *
     * <p>Empty/blank input is treated as the history bound — returns an empty feed without firing a
     * drift signal, because jut.su's history-end response is legitimately a 0-byte body.
     */
    public JutsuNoticeFeed parse(@Nullable String html, int requestedCursor) {
        if (html == null || html.isBlank()) {
            // History bound: legitimately empty, NOT drift.
            return new JutsuNoticeFeed(requestedCursor, List.of());
        }
        Document doc = Jsoup.parseBodyFragment(html);
        Elements blocks = doc.select(NOTICE_BLOCK_SELECTOR);
        if (blocks.isEmpty()) {
            // Body is non-empty but contained no notice blocks — drift.
            ctx.observe(
                    JutsuDriftSignal.SELECTOR_MISS,
                    "notice feed body has no " + NOTICE_BLOCK_SELECTOR + " entries");
            return new JutsuNoticeFeed(requestedCursor, List.of());
        }
        List<JutsuNoticeEntry> entries = new ArrayList<>(blocks.size());
        for (Element block : blocks) {
            JutsuNoticeEntry entry = parseEntry(block);
            if (entry != null) entries.add(entry);
        }
        return new JutsuNoticeFeed(requestedCursor, entries);
    }

    @Nullable
    private JutsuNoticeEntry parseEntry(Element block) {
        Element titleAnchor = block.selectFirst(NOTICE_TITLE_SELECTOR);
        if (titleAnchor == null) {
            ctx.observe(
                    JutsuDriftSignal.SELECTOR_MISS,
                    "notice entry missing " + NOTICE_TITLE_SELECTOR);
            return null;
        }
        String title = titleAnchor.text().trim();
        String episodeUrl = titleAnchor.attr("href").trim();
        if (title.isEmpty() || episodeUrl.isEmpty()) {
            ctx.observe(JutsuDriftSignal.SCHEMA_VIOLATION, "notice entry has empty title or href");
            return null;
        }
        String slug;
        int season;
        int episode;
        Matcher m = URL_PATTERN.matcher(episodeUrl);
        if (m.find()) {
            slug = m.group(1).toLowerCase(Locale.ROOT);
            season = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            episode = Integer.parseInt(m.group(3));
        } else {
            Matcher v = URL_PATTERN_VARIANT.matcher(episodeUrl);
            if (!v.find()) {
                ctx.observe(
                        JutsuDriftSignal.SCHEMA_VIOLATION,
                        "notice entry url doesn't match episode pattern: " + episodeUrl);
                return null;
            }
            slug = v.group(1).toLowerCase(Locale.ROOT);
            season = Integer.parseInt(v.group(2));
            episode = Integer.parseInt(v.group(3));
        }

        String thumbnail = null;
        Element thumbImg = block.selectFirst(NOTICE_THUMBNAIL_SELECTOR);
        if (thumbImg != null) {
            String src = thumbImg.attr("src").trim();
            if (!src.isEmpty()) thumbnail = src;
        }

        Element dateEl = block.selectFirst(NOTICE_DATE_SELECTOR);
        if (dateEl == null) {
            ctx.observe(
                    JutsuDriftSignal.SELECTOR_MISS, "notice entry missing " + NOTICE_DATE_SELECTOR);
            return null;
        }
        String relativeDate = dateEl.text().trim();
        if (relativeDate.isEmpty()) {
            ctx.observe(JutsuDriftSignal.SCHEMA_VIOLATION, "notice entry has empty relative date");
            return null;
        }

        return new JutsuNoticeEntry(
                slug, season, episode, title, episodeUrl, thumbnail, relativeDate);
    }
}
