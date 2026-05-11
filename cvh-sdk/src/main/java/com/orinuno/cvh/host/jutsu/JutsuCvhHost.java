package com.orinuno.cvh.host.jutsu;

import com.orinuno.cvh.host.CvhHostPageParser;
import com.orinuno.cvh.model.AnimeContent;
import com.orinuno.cvh.model.RatingInfo;
import com.orinuno.cvh.parser.CvhPlayerAttributeParser;
import com.orinuno.cvh.parser.CvhUrlParser;
import com.orinuno.cvh.parser.JsonLdRatingParser;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * jut-su.works (DLE CMS) page parser. Selectors target the {@code .jut-full_detal} info block,
 * {@code .jut-full_bg} poster container, the title {@code h1}, and the {@code <video-player>}
 * custom element CVH renders inside {@code #tab-2}.
 *
 * <p>{@code data-aggregator} falls back to the SDK's {@link
 * com.orinuno.cvh.CvhConfig#defaultAggregator()} via a constructor parameter — passing the live
 * config value at registration time means callers who tune the default see it reflected here.
 */
public final class JutsuCvhHost implements CvhHostPageParser {

    private static final List<String> TITLE_SUFFIXES_TO_STRIP =
            List.of(" смотреть аниме онлайн", " смотреть онлайн", " онлайн");

    private final String defaultAggregator;

    public JutsuCvhHost(String defaultAggregator) {
        if (defaultAggregator == null || defaultAggregator.isBlank()) {
            throw new IllegalArgumentException("defaultAggregator is required");
        }
        this.defaultAggregator = defaultAggregator;
    }

    @Override
    public String hostId() {
        return "jutsu";
    }

    @Override
    public boolean supports(URI pageUrl) {
        if (pageUrl == null) {
            return false;
        }
        String host = pageUrl.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase();
        return host.equals("jut-su.works") || host.endsWith(".jut-su.works");
    }

    @Override
    public AnimeContent parse(String html, String pageUrl) {
        Document doc = Jsoup.parse(html == null ? "" : html, pageUrl == null ? "" : pageUrl);
        @Nullable RatingInfo rating = JsonLdRatingParser.parse(doc).orElse(null);
        return new AnimeContent(
                CvhUrlParser.extractSlug(pageUrl),
                pageUrl,
                cleanTitle(doc.selectFirst("h1")),
                parseInfoField(doc, JutsuSelectors.LABEL_ORIGINAL),
                parseDescription(doc),
                parseGenres(doc),
                parseInfoField(doc, JutsuSelectors.LABEL_RELEASE_DATE),
                parseInfoField(doc, JutsuSelectors.LABEL_COUNTRY),
                parsePoster(doc),
                rating,
                CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_TITLE_ID),
                CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_PUBLISHER_ID),
                CvhPlayerAttributeParser.attrOrDefault(
                        doc, CvhPlayerAttributeParser.ATTR_AGGREGATOR, defaultAggregator),
                CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_PRIORITY_VOICE),
                parseKodikSrc(doc));
    }

    @Nullable
    private static String cleanTitle(@Nullable Element h1) {
        if (h1 == null) {
            return null;
        }
        String text = h1.text();
        for (String suffix : TITLE_SUFFIXES_TO_STRIP) {
            if (text.endsWith(suffix)) {
                text = text.substring(0, text.length() - suffix.length());
            }
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Nullable
    private static String parseInfoField(Document doc, String label) {
        for (Element li : doc.select(JutsuSelectors.INFO_LIST_ITEM)) {
            String key = li.ownText().replace(":", "").trim();
            if (key.equalsIgnoreCase(label)) {
                Element span = li.selectFirst("span");
                if (span != null) {
                    String v = span.text().trim();
                    return v.isEmpty() ? null : v;
                }
                return null;
            }
        }
        return null;
    }

    private static List<String> parseGenres(Document doc) {
        for (Element li : doc.select(JutsuSelectors.INFO_LIST_ITEM)) {
            String key = li.ownText().replace(":", "").trim();
            if (key.equalsIgnoreCase(JutsuSelectors.LABEL_GENRES)) {
                return li.select("a").stream()
                        .map(Element::text)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
            }
        }
        return List.of();
    }

    @Nullable
    private static String parseDescription(Document doc) {
        Element container = doc.selectFirst(JutsuSelectors.DETAIL_DESCRIPTION_CONTAINER);
        if (container == null) {
            return null;
        }
        String joined =
                container.select("p").stream()
                        .map(Element::text)
                        .map(String::trim)
                        .filter(t -> !t.isEmpty())
                        .collect(Collectors.joining("\n\n"));
        return joined.isEmpty() ? null : joined;
    }

    @Nullable
    private static String parsePoster(Document doc) {
        Element bg = doc.selectFirst(JutsuSelectors.POSTER_CONTAINER);
        if (bg != null) {
            Element img = bg.selectFirst("img");
            if (img != null) {
                String src = img.absUrl("src");
                if (!src.isEmpty()) {
                    return src;
                }
                String rawSrc = img.attr("src").trim();
                if (!rawSrc.isEmpty()) {
                    return rawSrc;
                }
            }
        }
        Element og = doc.selectFirst(JutsuSelectors.META_OG_IMAGE);
        if (og != null) {
            String content = og.attr("content").trim();
            return content.isEmpty() ? null : content;
        }
        return null;
    }

    @Nullable
    private static String parseKodikSrc(Document doc) {
        Element tab1 = doc.selectFirst(JutsuSelectors.KODIK_TAB);
        if (tab1 == null) {
            return null;
        }
        Element iframe = tab1.selectFirst("iframe");
        if (iframe == null) {
            return null;
        }
        String dataSrc = iframe.attr("data-src").trim();
        if (!dataSrc.isEmpty()) {
            return dataSrc;
        }
        String src = iframe.attr("src").trim();
        return src.isEmpty() ? null : src;
    }
}
