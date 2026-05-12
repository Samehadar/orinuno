/*
 * KodikDumpsProperties — ADR 0021 §D5.
 *
 * Public Kodik dump-endpoint config (DUMP-1 + DUMP-2). Restored from
 * orinuno-app's retired OrinunoProperties.DumpsProperties when D5 ported
 * the slice into orinuno-source-kodik. Prefix: orinuno.source-kodik.dumps.*.
 * Defaults stay conservative (enabled=false, download-body=false) so the
 * resurrected slice is dormant on every existing deployment until an
 * operator opts in.
 *
 * Knobs:
 *  - enabled               — top-level toggle for KodikDumpService HEAD poller +
 *                            DumpScheduler. Off → the bean tree exists but
 *                            scheduler does nothing and pollAll() short-circuits.
 *  - download-body         — second safety gate consumed by KodikDumpBootstrapService.
 *                            HEAD-only watcher does not need it; bootstrap() throws
 *                            IllegalStateException unless this is flipped. Designed
 *                            so accidentally enabling `dumps.enabled` cannot trigger
 *                            multi-GB GETs without a deliberate second flip.
 *  - base-url              — defaults to the public Kodik dumps host. Override only
 *                            for staging mirrors / HAR-replay servers.
 *  - poll-interval-minutes — cadence for the HEAD watcher. Default 60min; dumps
 *                            refresh slower than that, HEAD-only adds zero CDN load.
 *  - initial-delay-seconds — first poll after boot (default 30s) so we don't hammer
 *                            the host during smoke / health checks.
 *  - request-timeout-seconds — applied to both HEAD watcher and bootstrap stream
 *                            (the bootstrap path scales it x4 internally).
 *  - calendar / serials / films — per-dump enable + path overrides. Path defaults
 *                            match the public Kodik convention (`calendar.json`,
 *                            etc.); changing them is only meaningful when pointing
 *                            base-url at a mirror that renames the artifacts.
 */
package com.orinuno.source.kodik.configuration;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.dumps")
public class KodikDumpsProperties {

    private boolean enabled = false;
    private String baseUrl = "https://dumps.kodikres.com";
    private long pollIntervalMinutes = 60;
    private long initialDelaySeconds = 30;
    private long requestTimeoutSeconds = 30;
    private boolean downloadBody = false;
    private DumpEntry calendar = new DumpEntry(true, "calendar.json");
    private DumpEntry serials = new DumpEntry(true, "serials.json");
    private DumpEntry films = new DumpEntry(true, "films.json");

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DumpEntry {
        private boolean enabled;
        private String path;
    }
}
