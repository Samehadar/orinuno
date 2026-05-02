package com.orinuno.service.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kodik.sdk.drift.DriftDetector;
import com.kodik.sdk.drift.DriftSamplingProperties;
import com.orinuno.client.calendar.CalendarFetchResult;
import com.orinuno.client.calendar.KodikCalendarHttpClient;
import com.orinuno.client.dto.calendar.KodikCalendarEntryDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.service.metrics.KodikCalendarMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live integration test against {@code https://dumps.kodikres.com/calendar.json} (IDEA-AP-5).
 * Tagged {@code e2e} so the default {@code mvn test} skips it; opt-in via {@code mvn test -Pe2e}.
 *
 * <p>Independently controlled by {@code ORINUNO_LIVE_CALENDAR_TEST=true} so operators can run it
 * outside the {@code e2e} profile when validating an upstream change quickly. Both gates default to
 * off to keep CI hermetic.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "ORINUNO_LIVE_CALENDAR_TEST", matches = "(?i)true|1|yes")
class KodikCalendarLiveIntegrationTest {

    @Test
    @DisplayName("dumps.kodikres.com/calendar.json returns a non-empty array we can parse")
    void liveFetchSucceeds() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getCalendar().setRequestTimeoutSeconds(15);

        ObjectMapper mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        KodikCalendarHttpClient client =
                new KodikCalendarHttpClient(
                        properties,
                        mapper,
                        new DriftDetector(DriftSamplingProperties.defaults()),
                        new KodikCalendarMetrics(new SimpleMeterRegistry()));

        CalendarFetchResult first = client.fetch().block();
        assertThat(first).isNotNull();
        assertThat(first.entries()).isNotEmpty();
        assertThat(first.fetchedAt()).isNotNull();

        List<KodikCalendarEntryDto> entries = first.entries();
        KodikCalendarEntryDto sample = entries.get(0);
        assertThat(sample.anime()).isNotNull();
        assertThat(sample.anime().id()).isNotBlank();
        assertThat(sample.kind()).isNotBlank();
        assertThat(sample.status()).isNotBlank();

        CalendarFetchResult second = client.fetch().block();
        assertThat(second).isNotNull();
        assertThat(second.entries()).isNotEmpty();
    }
}
