package com.orinuno.service.dumps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.http.RotatingUserAgentProvider;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import com.orinuno.service.ContentService;
import java.io.InputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikDumpBootstrapServiceTest {

    private OrinunoProperties properties;
    private ContentService contentService;
    private KodikDumpBootstrapService service;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getDumps().setEnabled(true);
        properties.getDumps().setBaseUrl("https://dumps.kodikres.com");
        properties.getDumps().setDownloadBody(true);

        contentService = mock(ContentService.class);
        when(contentService.findOrCreateContent(any(KodikContent.class)))
                .thenAnswer(
                        inv -> {
                            KodikContent c = inv.getArgument(0, KodikContent.class);
                            if (c == null) return null;
                            c.setId(System.nanoTime());
                            return c;
                        });

        service =
                new KodikDumpBootstrapService(
                        properties, contentService, new RotatingUserAgentProvider());
    }

    @Test
    @DisplayName(
            "bootstrap throws IllegalStateException when download-body=false (multi-GB safety"
                    + " gate)")
    void bootstrapRefusesWhenDownloadBodyDisabled() {
        properties.getDumps().setDownloadBody(false);
        assertThatThrownBy(() -> service.bootstrap("calendar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("bootstrap rejects unknown dump names with a helpful error")
    void bootstrapRejectsUnknownDumpName() {
        assertThatThrownBy(() -> service.bootstrap("nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown dump name");
    }

    @Test
    @DisplayName(
            "bootstrapFromStream parses fixture, calls findOrCreateContent + saveVariants for"
                    + " every well-formed entry, reports counts")
    void bootstrapFromStreamUpsertsEveryWellFormedEntry() throws Exception {
        try (InputStream fixture = getClass().getResourceAsStream("films-fixture.json")) {
            KodikDumpBootstrapService.BootstrapResult result =
                    service.bootstrapFromStream("films", fixture);

            assertThat(result.dumpName()).isEqualTo("films");
            assertThat(result.parsed()).isEqualTo(4);
            assertThat(result.inserted())
                    .as(
                            "every parsed entry should reach the upsert path; the empty-id object"
                                    + " is still parsed (Lombok defaults), but findOrCreateContent"
                                    + " is mocked to accept everything")
                    .isEqualTo(4);

            verify(contentService, atLeastOnce()).findOrCreateContent(any());
            verify(contentService, atLeastOnce()).saveVariants(any());
        }
    }

    @Test
    @DisplayName(
            "bootstrapFromStream counts a contentService failure as skipped, keeps the stream"
                    + " moving (regression: one bad row never aborts the bootstrap)")
    void bootstrapFromStreamAbsorbsContentServiceFailures() throws Exception {
        when(contentService.findOrCreateContent(any(KodikContent.class)))
                .thenAnswer(
                        inv -> {
                            KodikContent c = inv.getArgument(0, KodikContent.class);
                            if (c != null && "Foreign Film".equals(c.getTitle())) {
                                throw new RuntimeException("simulated DB violation");
                            }
                            if (c != null) c.setId(System.nanoTime());
                            return c;
                        });

        try (InputStream fixture = getClass().getResourceAsStream("films-fixture.json")) {
            KodikDumpBootstrapService.BootstrapResult result =
                    service.bootstrapFromStream("films", fixture);

            assertThat(result.parsed()).isEqualTo(4);
            assertThat(result.skipped())
                    .as("Foreign Film row should be skipped, others should pass")
                    .isEqualTo(1);
            assertThat(result.inserted()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("buildUrl normalises trailing slash on the base URL")
    void buildUrlNormalisesTrailingSlash() {
        properties.getDumps().setBaseUrl("https://dumps.kodikres.com/");
        OrinunoProperties.DumpsProperties.DumpEntry entry =
                new OrinunoProperties.DumpsProperties.DumpEntry(true, "films.json");
        assertThat(service.buildUrl(entry)).isEqualTo("https://dumps.kodikres.com/films.json");
    }
}
