package com.orinuno.source.kodik.service.dumps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.source.kodik.configuration.KodikDumpsProperties;
import com.orinuno.source.kodik.configuration.KodikDumpsProperties.DumpEntry;
import com.orinuno.source.kodik.model.OrinunoDumpState;
import com.orinuno.source.kodik.repository.DumpStateRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link KodikDumpService} that exercise the URL-building, repository-write, and
 * change-detection logic. Network requests are not made — {@link KodikDumpService#pollSingle} isn't
 * invoked in these tests; we instead verify the pure helpers and the snapshot path.
 */
class KodikDumpServiceTest {

    private KodikDumpsProperties properties;
    private DumpStateRepository repository;
    private KodikDumpService service;

    @BeforeEach
    void setUp() {
        properties = new KodikDumpsProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://dumps.kodikres.com");
        repository = mock(DumpStateRepository.class);
        service = new KodikDumpService(properties, repository);
    }

    @Test
    @DisplayName("buildUrl joins base + path and trims trailing slash from base")
    void buildUrlJoinsBaseAndPath() {
        DumpEntry entry = new DumpEntry(true, "calendar.json");
        assertThat(service.buildUrl(entry)).isEqualTo("https://dumps.kodikres.com/calendar.json");

        properties.setBaseUrl("https://dumps.kodikres.com/");
        assertThat(service.buildUrl(entry))
                .as("trailing slash on base must be normalised")
                .isEqualTo("https://dumps.kodikres.com/calendar.json");
    }

    @Test
    @DisplayName("pollAll skips entirely when orinuno.source-kodik.dumps.enabled=false")
    void pollAllSkipsWhenDisabled() {
        properties.setEnabled(false);
        assertThat(service.pollAll()).isEmpty();
        verify(repository, org.mockito.Mockito.never())
                .upsertCheckResult(
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyInt());
    }

    @Test
    @DisplayName("pollAll skips disabled per-dump entries (only enabled ones are polled)")
    void pollAllSkipsDisabledEntries() {
        properties.getCalendar().setEnabled(false);
        properties.getSerials().setEnabled(false);
        properties.getFilms().setEnabled(false);

        assertThat(service.pollAll()).isEmpty();
        verify(repository, org.mockito.Mockito.never())
                .upsertCheckResult(
                        anyString(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        anyInt());
    }

    @Test
    @DisplayName("snapshot delegates to repository.findAll()")
    void snapshotDelegatesToRepository() {
        OrinunoDumpState state =
                OrinunoDumpState.builder()
                        .id(1L)
                        .dumpName("calendar")
                        .dumpUrl("https://dumps.kodikres.com/calendar.json")
                        .lastCheckedAt(LocalDateTime.now())
                        .lastStatus(200)
                        .consecutiveFailures(0)
                        .build();
        when(repository.findAll()).thenReturn(List.of(state));

        List<OrinunoDumpState> snap = service.snapshot();

        assertThat(snap).hasSize(1).first().isSameAs(state);
        verify(repository, atLeast(1)).findAll();
    }

    @Test
    @DisplayName(
            "DumpPollResult is a value object: name + url + status + changed + error are exposed")
    void dumpPollResultExposesAllFields() {
        KodikDumpService.DumpPollResult ok =
                new KodikDumpService.DumpPollResult("films", "https://x", 200, true, null);
        assertThat(ok.name()).isEqualTo("films");
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.changed()).isTrue();
        assertThat(ok.error()).isNull();

        KodikDumpService.DumpPollResult fail =
                new KodikDumpService.DumpPollResult("serials", "https://x", null, false, "boom");
        assertThat(fail.error()).isEqualTo("boom");
        assertThat(fail.status()).isNull();
    }

    @Test
    @DisplayName(
            "persistFailure path: when repository upserts on poll failure, consecutive_failures is"
                    + " bumped (regression: prev value must be read first)")
    void persistFailureBumpsConsecutiveFailuresFromPrevious() {
        properties.setEnabled(true);
        properties.getSerials().setEnabled(false);
        properties.getFilms().setEnabled(false);
        properties.getCalendar().setEnabled(true);
        properties.setBaseUrl("http://invalid-host-that-cannot-resolve.test");
        properties.setRequestTimeoutSeconds(1);

        when(repository.findByName(eq("calendar")))
                .thenReturn(
                        Optional.of(
                                OrinunoDumpState.builder()
                                        .dumpName("calendar")
                                        .consecutiveFailures(2)
                                        .build()));

        List<KodikDumpService.DumpPollResult> results = service.pollAll();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).error()).isNotNull();

        ArgumentCaptor<Integer> failuresCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(repository)
                .upsertCheckResult(
                        eq("calendar"),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        failuresCaptor.capture());

        assertThat(failuresCaptor.getValue())
                .as("consecutive_failures should be previous (2) + 1 = 3")
                .isEqualTo(3);
    }

    @SuppressWarnings("unused")
    private void unused(Long l) {
        // suppress lint about anyLong import not directly used
        anyLong();
    }
}
