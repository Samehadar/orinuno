package com.orinuno.cvh.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class CvhPlayerAttributeParserTest {

    @Test
    void extractsAttrsFromVideoPlayerElement() {
        Document doc =
                Jsoup.parse(
                        "<html><body>"
                                + "<video-player data-title-id=\"61192\" data-publisher-id=\"910\""
                                + " data-aggregator=\"mali\" priority-voice=\"AniStar\">"
                                + "</video-player></body></html>");
        assertThat(CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_TITLE_ID))
                .isEqualTo("61192");
        assertThat(CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_PUBLISHER_ID))
                .isEqualTo("910");
        assertThat(CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_AGGREGATOR))
                .isEqualTo("mali");
        assertThat(CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_PRIORITY_VOICE))
                .isEqualTo("AniStar");
    }

    @Test
    void returnsNullWhenPlayerMissing() {
        Document doc = Jsoup.parse("<html><body>nothing here</body></html>");
        assertThat(CvhPlayerAttributeParser.attr(doc, CvhPlayerAttributeParser.ATTR_TITLE_ID))
                .isNull();
        assertThat(CvhPlayerAttributeParser.findPlayer(doc)).isEmpty();
    }

    @Test
    void emptyAttrFallsBackToDefault() {
        Document doc =
                Jsoup.parse(
                        "<html><body><video-player"
                                + " data-aggregator=\"\"></video-player></body></html>");
        assertThat(
                        CvhPlayerAttributeParser.attrOrDefault(
                                doc, CvhPlayerAttributeParser.ATTR_AGGREGATOR, "mali"))
                .isEqualTo("mali");
    }
}
