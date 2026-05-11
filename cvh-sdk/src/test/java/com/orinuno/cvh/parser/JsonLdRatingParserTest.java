package com.orinuno.cvh.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.model.RatingInfo;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class JsonLdRatingParserTest {

    @Test
    void parsesGraphEnvelopedSchema() {
        String html =
                """
                <html><head>
                <script type="application/ld+json">
                {"@graph":[{"@type":"Movie","datePublished":"2026-01-09",
                "aggregateRating":{"ratingValue":"8.4","ratingCount":"1234"}}]}
                </script></head><body/></html>
                """;
        Document doc = Jsoup.parse(html);
        Optional<RatingInfo> r = JsonLdRatingParser.parse(doc);
        assertThat(r).isPresent();
        assertThat(r.get().value()).isEqualTo("8.4");
        assertThat(r.get().count()).isEqualTo("1234");
        assertThat(r.get().contentType()).isEqualTo("Movie");
        assertThat(r.get().datePublished()).isEqualTo("2026-01-09");
    }

    @Test
    void parsesBareObject() {
        String html =
                """
                <script type="application/ld+json">
                {"@type":"TVSeries","aggregateRating":{"ratingValue":"9.0"}}
                </script>
                """;
        Optional<RatingInfo> r = JsonLdRatingParser.parse(Jsoup.parse(html));
        assertThat(r).isPresent();
        assertThat(r.get().value()).isEqualTo("9.0");
        assertThat(r.get().contentType()).isEqualTo("TVSeries");
        assertThat(r.get().count()).isNull();
    }

    @Test
    void absentScriptReturnsEmpty() {
        assertThat(JsonLdRatingParser.parse(Jsoup.parse("<html/>"))).isEmpty();
    }

    @Test
    void malformedJsonReturnsEmpty() {
        Document doc = Jsoup.parse("<script type=\"application/ld+json\">not json {</script>");
        assertThat(JsonLdRatingParser.parse(doc)).isEmpty();
    }

    @Test
    void missingAggregateRatingReturnsEmpty() {
        Document doc =
                Jsoup.parse(
                        "<script type=\"application/ld+json\">"
                                + "{\"@type\":\"Movie\",\"name\":\"X\"}"
                                + "</script>");
        assertThat(JsonLdRatingParser.parse(doc)).isEmpty();
    }
}
