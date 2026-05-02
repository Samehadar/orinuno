package com.orinuno.service.download.hls;

import com.orinuno.configuration.OrinunoProperties;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * DOWNLOAD-PARALLEL — wraps the ffmpeg invocation that turns a downloaded HLS stream into an MP4.
 * Two strategies:
 *
 * <ul>
 *   <li>{@link OrinunoProperties.HlsProperties.FfmpegMode#SINGLE_INPUT} — legacy. Caller already
 *       concatenated every segment into one big {@code .ts}; we run {@code ffmpeg -i big.ts -c copy
 *       -movflags +faststart out.mp4}. Optimal for small playlists (Kodik anime episodes are
 *       usually a few hundred segments × ~1 MB).
 *   <li>{@link OrinunoProperties.HlsProperties.FfmpegMode#CONCAT_DEMUXER} — keeps each segment as
 *       its own file, writes a {@code concat.txt} manifest in playback order, then runs {@code
 *       ffmpeg -f concat -safe 0 -i concat.txt -c copy -movflags +faststart out.mp4}. Avoids the
 *       giant intermediate {@code .ts} file — useful for multi-GB HLS dumps where the host disk
 *       can't comfortably hold the concatenated copy + the final MP4 simultaneously.
 * </ul>
 *
 * <p>Both paths use stream-copy ({@code -c copy}) — no re-encoding, no quality loss, no CPU spike.
 * MP4 fast-start ({@code -movflags +faststart}) moves the moov atom to the front so browsers can
 * start playing before the whole file is downloaded.
 */
@Slf4j
@Component
public class FfmpegRemuxer {

    private final RemuxOptions defaultOptions;
    private final ProcessExecutor executor;

    public FfmpegRemuxer() {
        this(RemuxOptions.defaults(), ProcessExecutor.system());
    }

    FfmpegRemuxer(RemuxOptions defaultOptions, ProcessExecutor executor) {
        this.defaultOptions = defaultOptions;
        this.executor = executor;
    }

    /**
     * Single-input remux. Returns the MP4 path on success; on ffmpeg failure / timeout returns the
     * input {@code .ts} path so the caller can serve the partial file.
     */
    public Path remuxSingleInput(Path tsPath) throws Exception {
        return remuxSingleInput(tsPath, defaultOptions);
    }

    public Path remuxSingleInput(Path tsPath, RemuxOptions options) throws Exception {
        Path mp4Path = swapToMp4Suffix(tsPath);
        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpegBinary());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(tsPath.toAbsolutePath().toString());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(mp4Path.toAbsolutePath().toString());
        return runFfmpeg(cmd, mp4Path, tsPath, options);
    }

    /**
     * Concat-demuxer remux. Writes a {@code concat.txt} listing every segment in playback order
     * (alongside the segments) and runs ffmpeg's concat demuxer. {@code segmentDir} must contain
     * every segment file referenced in {@code orderedSegmentFiles}; the concat manifest is written
     * to {@code segmentDir/concat.txt} and removed on success.
     */
    public Path remuxConcatDemuxer(
            Path segmentDir, List<Path> orderedSegmentFiles, Path mp4OutputPath) throws Exception {
        return remuxConcatDemuxer(segmentDir, orderedSegmentFiles, mp4OutputPath, defaultOptions);
    }

    public Path remuxConcatDemuxer(
            Path segmentDir,
            List<Path> orderedSegmentFiles,
            Path mp4OutputPath,
            RemuxOptions options)
            throws Exception {
        Files.createDirectories(segmentDir);
        Path concatManifest = segmentDir.resolve("concat.txt");
        StringBuilder body = new StringBuilder();
        for (Path segment : orderedSegmentFiles) {
            String absolute = segment.toAbsolutePath().toString().replace("'", "'\\''");
            body.append("file '").append(absolute).append("'\n");
        }
        Files.writeString(concatManifest, body.toString(), StandardCharsets.UTF_8);

        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpegBinary());
        cmd.add("-y");
        cmd.add("-f");
        cmd.add("concat");
        cmd.add("-safe");
        cmd.add("0");
        cmd.add("-i");
        cmd.add(concatManifest.toAbsolutePath().toString());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(mp4OutputPath.toAbsolutePath().toString());

        try {
            return runFfmpeg(cmd, mp4OutputPath, null, options);
        } finally {
            try {
                Files.deleteIfExists(concatManifest);
            } catch (Exception ignored) {
            }
        }
    }

    private Path runFfmpeg(List<String> cmd, Path mp4Path, Path fallbackPath, RemuxOptions options)
            throws Exception {
        log.info("Remuxing via ffmpeg ({} args)", cmd.size());
        ProcessExecutor.Result result =
                executor.run(cmd, options.ffmpegTimeoutSeconds(), TimeUnit.SECONDS);
        if (result == null) {
            log.warn("ffmpeg returned null result, keeping fallback path");
            return fallbackPath != null ? fallbackPath : mp4Path;
        }
        if (result.timedOut()) {
            log.warn("ffmpeg timed out after {}s, keeping input", options.ffmpegTimeoutSeconds());
            return fallbackPath != null ? fallbackPath : mp4Path;
        }
        if (result.exitCode() == 0 && Files.exists(mp4Path) && Files.size(mp4Path) > 0) {
            log.info(
                    "Remux complete: {} ({} MB)",
                    mp4Path.getFileName(),
                    Files.size(mp4Path) / (1024 * 1024));
            return mp4Path;
        }
        log.warn(
                "ffmpeg failed (exit={}), keeping input. ffmpeg-stderr-snippet={}",
                result.exitCode(),
                result.stdoutSnippet());
        return fallbackPath != null ? fallbackPath : mp4Path;
    }

    static Path swapToMp4Suffix(Path tsPath) {
        String s = tsPath.toString();
        if (s.endsWith(".ts")) {
            return Path.of(s.substring(0, s.length() - 3) + ".mp4");
        }
        return Path.of(s + ".mp4");
    }

    /** Bag of overrides separated from Spring config so we can default for plain {@code new}. */
    public record RemuxOptions(String ffmpegBinary, int ffmpegTimeoutSeconds) {
        public static RemuxOptions defaults() {
            return new RemuxOptions("ffmpeg", 120);
        }
    }

    /** Indirection over {@link Process} for testability. */
    public interface ProcessExecutor {
        Result run(List<String> command, long timeout, TimeUnit unit) throws Exception;

        record Result(int exitCode, boolean timedOut, String stdoutSnippet) {}

        static ProcessExecutor system() {
            return (cmd, timeout, unit) -> {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                StringBuilder buf = new StringBuilder();
                try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (buf.length() < 2048) {
                            buf.append(line).append('\n');
                        }
                        log.debug("ffmpeg: {}", line);
                    }
                }
                boolean finished = process.waitFor(timeout, unit);
                if (!finished) {
                    process.destroyForcibly();
                    return new Result(-1, true, buf.toString());
                }
                return new Result(process.exitValue(), false, buf.toString());
            };
        }
    }
}
