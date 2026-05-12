/*
 * KodikStorageProperties — ADR 0021 §C2.1 (partial Block E2).
 *
 * Local-file storage knobs for VideoDownloadService. Replaces the
 * legacy OrinunoProperties.StorageProperties subtree. Property prefix:
 * orinuno.source-kodik.storage.*. Defaults preserve legacy values.
 */
package com.orinuno.source.kodik.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.storage")
public class KodikStorageProperties {

    private String basePath = "./data/videos";
    private long maxDiskUsageMb = 10240;
}
