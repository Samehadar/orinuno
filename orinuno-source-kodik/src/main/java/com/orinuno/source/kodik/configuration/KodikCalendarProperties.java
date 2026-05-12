/*
 * KodikCalendarProperties — ADR 0021 §E2.
 *
 * Calendar slice config (formerly OrinunoProperties.CalendarProperties).
 * Prefix: orinuno.source-kodik.calendar.*. Defaults preserve legacy
 * orinuno-app values (CAL-6 delta watcher off; calendar service enabled
 * but only polls when actively called).
 */
package com.orinuno.source.kodik.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.calendar")
public class KodikCalendarProperties {

    private boolean enabled = true;
    private String url = "https://dumps.kodikres.com/calendar.json";
    private long cacheTtlSeconds = 300;
    private long requestTimeoutSeconds = 10;
    private long maxResponseBytes = 4L * 1024 * 1024;
    private DeltaWatcherProperties deltaWatcher = new DeltaWatcherProperties();

    @Data
    public static class DeltaWatcherProperties {
        private boolean enabled = false;
        private long pollIntervalMinutes = 5;
        private long initialDelaySeconds = 60;
    }
}
