package com.orinuno.jutsu.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class JutsuNoticeFeedTest {

    private static JutsuNoticeEntry sampleEntry(int episode) {
        return new JutsuNoticeEntry(
                "x",
                1,
                episode,
                "x: " + episode + " серия",
                "https://jut.su/x/episode-" + episode + ".html",
                null,
                "сегодня");
    }

    @Test
    void feedWithEntriesHasNextCursor() {
        List<JutsuNoticeEntry> entries = List.of(sampleEntry(1), sampleEntry(2), sampleEntry(3));
        JutsuNoticeFeed feed = new JutsuNoticeFeed(100, entries);

        assertThat(feed.hasEntries()).isTrue();
        assertThat(feed.nextCursor()).hasValue(97);
    }

    @Test
    void emptyFeedHasNoNextCursor() {
        JutsuNoticeFeed feed = new JutsuNoticeFeed(0, List.of());

        assertThat(feed.hasEntries()).isFalse();
        assertThat(feed.nextCursor()).isEmpty();
    }

    @Test
    void cursorUnderflowReturnsEmpty() {
        List<JutsuNoticeEntry> entries = List.of(sampleEntry(1), sampleEntry(2), sampleEntry(3));
        JutsuNoticeFeed feed = new JutsuNoticeFeed(2, entries);

        assertThat(feed.nextCursor()).isEmpty();
    }

    @Test
    void nullEntriesCollapseToEmptyList() {
        JutsuNoticeFeed feed = new JutsuNoticeFeed(10, null);

        assertThat(feed.entries()).isEmpty();
        assertThat(feed.hasEntries()).isFalse();
    }

    @Test
    void negativeCursorRejected() {
        assertThatThrownBy(() -> new JutsuNoticeFeed(-1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void entriesListIsImmutable() {
        List<JutsuNoticeEntry> mutable = new java.util.ArrayList<>();
        mutable.add(sampleEntry(1));
        JutsuNoticeFeed feed = new JutsuNoticeFeed(10, mutable);
        // Mutating the source list must not affect the feed.
        mutable.add(sampleEntry(2));

        assertThat(feed.entries()).hasSize(1);
        assertThatThrownBy(() -> feed.entries().add(sampleEntry(99)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
