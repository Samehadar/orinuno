package com.orinuno.client.dto.reference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic envelope for Kodik reference endpoints ({@code /translations/v2}, {@code /genres}, {@code
 * /countries}, {@code /years}, {@code /qualities/v2}).
 *
 * <p>Schema: {@code { time, total, results: [...] }}. The {@code results} array contains items
 * typed by {@code T}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class KodikReferenceResponse<T> {

    private String time;
    private Integer total;
    private List<T> results;
}
