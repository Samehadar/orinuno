package com.orinuno.source.jutsu.dto;

import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.util.List;

/** REST projection of {@link JutsuNoticeFeed}. */
@Schema(description = "One page of jut.su's upcoming-releases notice feed.")
public record JutsuNoticeFeedDto(
        @Schema(description = "Cursor (notice_id) used to fetch this page") int requestedCursor,
        @Schema(description = "Cursor for the next (older) page; null at the history bound")
                @Nullable
                Integer nextCursor,
        @Schema(description = "Notice entries newest-first") List<JutsuNoticeEntryDto> entries,
        @Schema(description = "true when this page contains at least one entry")
                boolean hasEntries) {

    public static JutsuNoticeFeedDto from(JutsuNoticeFeed feed) {
        return new JutsuNoticeFeedDto(
                feed.requestedCursor(),
                feed.nextCursor().orElse(null),
                feed.entries().stream().map(JutsuNoticeEntryDto::from).toList(),
                feed.hasEntries());
    }

    @Schema(description = "One announcement / release notice.")
    public record JutsuNoticeEntryDto(
            @Schema(example = "shokugyou-kanteishi") String slug,
            @Schema(example = "1") int season,
            @Schema(example = "6") int episode,
            @Schema(example = "Не герой, а временный инспектор: 6 серия") String title,
            @Schema(example = "https://jut.su/shokugyou-kanteishi/episode-6.html")
                    String episodeUrl,
            @Schema(nullable = true) @Nullable String thumbnailUrl,
            @Schema(
                            description =
                                    "Russian relative-date label as rendered by the site,"
                                            + " preserved verbatim",
                            example = "сегодня ночью")
                    String relativeDate) {

        public static JutsuNoticeEntryDto from(JutsuNoticeEntry e) {
            return new JutsuNoticeEntryDto(
                    e.slug(),
                    e.season(),
                    e.episode(),
                    e.title(),
                    e.episodeUrl(),
                    e.thumbnailUrl(),
                    e.relativeDate());
        }
    }
}
