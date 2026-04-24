package com.orinuno.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParseRequestDto {

    private String title;
    private String kinopoiskId;
    private String imdbId;
    private String shikimoriId;

    /** If true, also decode mp4 links after search. */
    @Builder.Default private boolean decodeLinks = false;
}
