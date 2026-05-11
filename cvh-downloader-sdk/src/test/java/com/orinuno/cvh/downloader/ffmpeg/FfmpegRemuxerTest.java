package com.orinuno.cvh.downloader.ffmpeg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegRemuxerTest {

    private static final class CapturingExecutor implements ProcessExecutor {
        List<String> lastCommand;
        Result result = new Result(0, false, "ok");

        @Override
        public Result run(List<String> command, long timeout, TimeUnit unit) {
            this.lastCommand = new ArrayList<>(command);
            return result;
        }
    }

    @Test
    void singleInputCommandShape(@TempDir Path dir) throws Exception {
        Path ts = dir.resolve("big.ts");
        Files.writeString(ts, "ignored");
        Path mp4 = dir.resolve("out.mp4");
        Files.writeString(mp4, "fake-mp4-output");

        CapturingExecutor exec = new CapturingExecutor();
        FfmpegRemuxer remuxer = new FfmpegRemuxer(FfmpegRemuxer.RemuxOptions.defaults(), exec);

        remuxer.remuxSingleInput(ts, mp4);

        assertThat(exec.lastCommand)
                .containsExactly(
                        "ffmpeg",
                        "-y",
                        "-i",
                        ts.toAbsolutePath().toString(),
                        "-c",
                        "copy",
                        "-movflags",
                        "+faststart",
                        mp4.toAbsolutePath().toString());
    }

    @Test
    void downloadDirectRejectsNonHttpScheme(@TempDir Path dir) {
        FfmpegRemuxer remuxer =
                new FfmpegRemuxer(FfmpegRemuxer.RemuxOptions.defaults(), new CapturingExecutor());
        assertThatThrownBy(
                        () ->
                                remuxer.downloadDirect(
                                        "file:///etc/passwd",
                                        dir.resolve("out.mp4"),
                                        "https://jut-su.works/",
                                        "UA"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadDirectRejectsRefererWithControlChars(@TempDir Path dir) {
        FfmpegRemuxer remuxer =
                new FfmpegRemuxer(FfmpegRemuxer.RemuxOptions.defaults(), new CapturingExecutor());
        assertThatThrownBy(
                        () ->
                                remuxer.downloadDirect(
                                        "https://x.test/m.mpd",
                                        dir.resolve("out.mp4"),
                                        "https://x\r\nInject: y/",
                                        "UA"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void downloadDirectAppendsRefererAndUserAgent(@TempDir Path dir) throws Exception {
        Path mp4 = dir.resolve("out.mp4");
        Files.writeString(mp4, "fake");
        CapturingExecutor exec = new CapturingExecutor();
        FfmpegRemuxer remuxer = new FfmpegRemuxer(FfmpegRemuxer.RemuxOptions.defaults(), exec);

        remuxer.downloadDirect("https://cdn.test/m.mpd", mp4, "https://jut-su.works/", "TestUA");

        assertThat(exec.lastCommand)
                .contains("-user_agent", "TestUA")
                .contains("-headers", "Referer: https://jut-su.works/\r\n")
                .contains("-i", "https://cdn.test/m.mpd");
    }

    @Test
    void timeoutMapsToIOException(@TempDir Path dir) {
        Path mp4 = dir.resolve("out.mp4");
        CapturingExecutor exec = new CapturingExecutor();
        exec.result = new ProcessExecutor.Result(-1, true, "killed");
        FfmpegRemuxer remuxer = new FfmpegRemuxer(FfmpegRemuxer.RemuxOptions.defaults(), exec);

        assertThatThrownBy(
                        () ->
                                remuxer.downloadDirect(
                                        "https://x.test/m.mpd", mp4, "https://r/", "UA"))
                .hasMessageContaining("ffmpeg timed out");
    }
}
