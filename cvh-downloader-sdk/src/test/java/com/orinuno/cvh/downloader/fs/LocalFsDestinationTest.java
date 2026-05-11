package com.orinuno.cvh.downloader.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFsDestinationTest {

    @Test
    void resolveSafeWithinBaseDir(@TempDir Path baseDir) {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Path resolved = fs.resolveSafe("video.mp4");
        assertThat(resolved.getParent()).isEqualTo(baseDir.toAbsolutePath().normalize());
        assertThat(resolved.getFileName().toString()).isEqualTo("video.mp4");
    }

    @Test
    void resolveSafeRejectsTraversal(@TempDir Path baseDir) {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        assertThatThrownBy(() -> fs.resolveSafe("../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> fs.resolveSafe("nested/../../../escape"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void commitMovesAtomically(@TempDir Path baseDir) throws Exception {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        Path src = baseDir.resolve("temp.part");
        Files.writeString(src, "payload");
        Path target = baseDir.resolve("final.mp4");

        fs.commit(src, target);

        assertThat(Files.exists(target)).isTrue();
        assertThat(Files.exists(src)).isFalse();
        assertThat(Files.readString(target)).isEqualTo("payload");
    }

    @Test
    void blankRelativeRejected(@TempDir Path baseDir) {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        assertThatThrownBy(() -> fs.resolveSafe("")).isInstanceOf(IllegalArgumentException.class);
    }
}
