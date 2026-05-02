package com.orinuno.service.download.hls;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.service.download.hls.FfmpegRemuxer.ProcessExecutor;
import com.orinuno.service.download.hls.FfmpegRemuxer.RemuxOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegRemuxerTest {

    @Test
    @DisplayName("swapToMp4Suffix swaps .ts → .mp4 (and appends when no .ts suffix)")
    void suffixSwap() {
        assertThat(FfmpegRemuxer.swapToMp4Suffix(Path.of("/tmp/a.ts")))
                .isEqualTo(Path.of("/tmp/a.mp4"));
        assertThat(FfmpegRemuxer.swapToMp4Suffix(Path.of("/tmp/a.video")))
                .isEqualTo(Path.of("/tmp/a.video.mp4"));
    }

    @Test
    @DisplayName("single-input invocation feeds ffmpeg the .ts and writes .mp4 next to it")
    void singleInputBuildsCorrectCommand(@TempDir Path tmp) throws Exception {
        Path ts = tmp.resolve("video.ts");
        Files.writeString(ts, "TS-bytes-stub");
        CapturingExecutor exec =
                new CapturingExecutor(0, false, ts.resolveSibling("video.mp4"), 100);
        FfmpegRemuxer remuxer = new FfmpegRemuxer(RemuxOptions.defaults(), exec);

        Path out = remuxer.remuxSingleInput(ts);

        assertThat(out).isEqualTo(ts.resolveSibling("video.mp4"));
        assertThat(exec.lastCommand)
                .startsWith("ffmpeg", "-y", "-i", ts.toAbsolutePath().toString())
                .contains("-c", "copy", "-movflags", "+faststart");
        assertThat(exec.lastCommand).endsWith(out.toAbsolutePath().toString());
    }

    @Test
    @DisplayName("ffmpeg failure (non-zero exit) returns the input .ts (caller can serve partial)")
    void singleInputFailureKeepsTs(@TempDir Path tmp) throws Exception {
        Path ts = tmp.resolve("video.ts");
        Files.writeString(ts, "TS-bytes-stub");
        CapturingExecutor exec = new CapturingExecutor(1, false, null, 0);
        FfmpegRemuxer remuxer = new FfmpegRemuxer(RemuxOptions.defaults(), exec);

        Path out = remuxer.remuxSingleInput(ts);
        assertThat(out).isEqualTo(ts);
    }

    @Test
    @DisplayName("ffmpeg timeout returns the input .ts")
    void singleInputTimeoutKeepsTs(@TempDir Path tmp) throws Exception {
        Path ts = tmp.resolve("video.ts");
        Files.writeString(ts, "TS-bytes-stub");
        CapturingExecutor exec = new CapturingExecutor(-1, true, null, 0);
        FfmpegRemuxer remuxer = new FfmpegRemuxer(RemuxOptions.defaults(), exec);

        Path out = remuxer.remuxSingleInput(ts);
        assertThat(out).isEqualTo(ts);
    }

    @Test
    @DisplayName("concat-demuxer writes concat.txt with quoted paths and feeds it to ffmpeg")
    void concatDemuxerBuildsManifest(@TempDir Path tmp) throws Exception {
        Path segDir = tmp.resolve("segs");
        Files.createDirectories(segDir);
        Path s1 = segDir.resolve("seg-001.ts");
        Path s2 = segDir.resolve("seg-002.ts");
        Files.writeString(s1, "a");
        Files.writeString(s2, "b");
        Path mp4 = tmp.resolve("out.mp4");
        CapturingExecutor exec = new CapturingExecutor(0, false, mp4, 200);
        FfmpegRemuxer remuxer = new FfmpegRemuxer(RemuxOptions.defaults(), exec);

        Path result = remuxer.remuxConcatDemuxer(segDir, List.of(s1, s2), mp4);

        assertThat(result).isEqualTo(mp4);
        assertThat(exec.lastCommand)
                .containsSequence("-f", "concat", "-safe", "0", "-i")
                .contains("-c", "copy", "-movflags", "+faststart");
        assertThat(exec.lastCommand).endsWith(mp4.toAbsolutePath().toString());
        assertThat(Files.exists(segDir.resolve("concat.txt")))
                .as("concat.txt is removed after ffmpeg run")
                .isFalse();
        assertThat(exec.concatManifestSnapshot)
                .contains("file '" + s1.toAbsolutePath() + "'")
                .contains("file '" + s2.toAbsolutePath() + "'");
    }

    @Test
    @DisplayName("concat-demuxer escapes single-quote in segment paths so ffmpeg parses correctly")
    void concatDemuxerEscapesQuotes(@TempDir Path tmp) throws Exception {
        Path segDir = tmp.resolve("seg's");
        Files.createDirectories(segDir);
        Path quoted = segDir.resolve("a's-001.ts");
        Files.writeString(quoted, "x");
        Path mp4 = tmp.resolve("out.mp4");
        CapturingExecutor exec = new CapturingExecutor(0, false, mp4, 50);
        FfmpegRemuxer remuxer = new FfmpegRemuxer(RemuxOptions.defaults(), exec);

        remuxer.remuxConcatDemuxer(segDir, List.of(quoted), mp4);

        assertThat(exec.concatManifestSnapshot).contains("'\\''").doesNotContain("'a's-001.ts'");
    }

    private static final class CapturingExecutor implements ProcessExecutor {
        private final int exitCode;
        private final boolean timedOut;
        private final Path outputToCreate;
        private final int outputBytesToWrite;
        private List<String> lastCommand;
        private String concatManifestSnapshot;

        CapturingExecutor(int exitCode, boolean timedOut, Path outputToCreate, int outputBytes) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.outputToCreate = outputToCreate;
            this.outputBytesToWrite = outputBytes;
        }

        @Override
        public Result run(List<String> command, long timeout, TimeUnit unit) throws Exception {
            lastCommand = new ArrayList<>(command);
            int idx = command.indexOf("-i");
            if (idx >= 0 && idx + 1 < command.size()) {
                Path input = Path.of(command.get(idx + 1));
                if (Files.exists(input) && input.getFileName().toString().equals("concat.txt")) {
                    concatManifestSnapshot = Files.readString(input, StandardCharsets.UTF_8);
                }
            }
            if (outputToCreate != null && exitCode == 0 && !timedOut) {
                byte[] payload = new byte[outputBytesToWrite];
                Files.write(outputToCreate, payload);
            }
            return new Result(exitCode, timedOut, "stub-stderr");
        }
    }

    @Test
    @DisplayName("system() returns a non-null executor (smoke check; no actual ffmpeg run)")
    void systemExecutorAvailable() throws IOException {
        assertThat(ProcessExecutor.system()).isNotNull();
    }
}
