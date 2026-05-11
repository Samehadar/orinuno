package com.orinuno.cvh.downloader.ffmpeg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Wraps the ffmpeg invocation that turns a downloaded HLS stream into an MP4, or downloads a DASH
 * manifest directly. Three modes:
 *
 * <ul>
 *   <li>{@link Mode#SINGLE_INPUT} — caller already concatenated every segment into one big {@code
 *       .ts}; we run {@code ffmpeg -i big.ts -c copy -movflags +faststart out.mp4}.
 *   <li>{@link Mode#CONCAT_DEMUXER} — each segment kept as its own file; we write a {@code
 *       concat.txt} manifest in playback order, then run ffmpeg's concat demuxer. Avoids a giant
 *       intermediate {@code .ts}.
 *   <li>{@link Mode#DIRECT_INPUT} — pass a remote URL (HLS master or DASH MPD) directly to ffmpeg
 *       with optional {@code Referer} header. Lets ffmpeg fetch and demux segments itself; used for
 *       DASH where the SDK has no native MPD parser.
 * </ul>
 *
 * <p>All modes use stream-copy ({@code -c copy}) — no re-encoding. MP4 fast-start ({@code -movflags
 * +faststart}) keeps the moov atom at the front so the file can be served progressively.
 *
 * <p>Command arguments are always passed as a {@link List} to {@link ProcessExecutor} which uses
 * {@link ProcessBuilder} (no shell interpretation) — values stay safe even if they contain spaces
 * or shell metacharacters.
 */
@Slf4j
public final class FfmpegRemuxer {

    public enum Mode {
        SINGLE_INPUT,
        CONCAT_DEMUXER,
        DIRECT_INPUT
    }

    private final RemuxOptions defaultOptions;
    private final ProcessExecutor executor;

    public FfmpegRemuxer() {
        this(RemuxOptions.defaults(), ProcessExecutor.system());
    }

    public FfmpegRemuxer(RemuxOptions defaultOptions, ProcessExecutor executor) {
        this.defaultOptions = defaultOptions;
        this.executor = executor;
    }

    public Path remuxSingleInput(Path tsPath, Path mp4OutputPath) throws Exception {
        return remuxSingleInput(tsPath, mp4OutputPath, defaultOptions);
    }

    public Path remuxSingleInput(Path tsPath, Path mp4OutputPath, RemuxOptions options)
            throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpegBinary());
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(tsPath.toAbsolutePath().toString());
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(mp4OutputPath.toAbsolutePath().toString());
        return runFfmpeg(cmd, mp4OutputPath, tsPath, options);
    }

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
            } catch (IOException ignored) {
                // best-effort cleanup
            }
        }
    }

    /**
     * Hands a remote URL (HLS master or DASH MPD) to ffmpeg with optional {@code Referer} and
     * {@code User-Agent} headers. Used when the SDK does not need fine-grained segment progress (we
     * get coarse status via the process exit code only).
     */
    public Path downloadDirect(
            String inputUrl, Path mp4OutputPath, String referer, String userAgent)
            throws Exception {
        return downloadDirect(inputUrl, mp4OutputPath, referer, userAgent, defaultOptions);
    }

    public Path downloadDirect(
            String inputUrl,
            Path mp4OutputPath,
            String referer,
            String userAgent,
            RemuxOptions options)
            throws Exception {
        if (inputUrl == null || inputUrl.isBlank()) {
            throw new IllegalArgumentException("inputUrl must not be blank");
        }
        if (!inputUrl.startsWith("https://") && !inputUrl.startsWith("http://")) {
            throw new IllegalArgumentException(
                    "inputUrl must use http(s):// scheme — got: " + safeScheme(inputUrl));
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(options.ffmpegBinary());
        cmd.add("-y");
        if (userAgent != null && !userAgent.isBlank()) {
            cmd.add("-user_agent");
            cmd.add(userAgent);
        }
        if (referer != null && !referer.isBlank()) {
            if (containsControlChar(referer)) {
                throw new IllegalArgumentException("referer contains control characters");
            }
            cmd.add("-headers");
            cmd.add("Referer: " + referer + "\r\n");
        }
        cmd.add("-i");
        cmd.add(inputUrl);
        cmd.add("-c");
        cmd.add("copy");
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(mp4OutputPath.toAbsolutePath().toString());
        return runFfmpeg(cmd, mp4OutputPath, null, options);
    }

    private Path runFfmpeg(List<String> cmd, Path mp4Path, Path fallbackPath, RemuxOptions options)
            throws Exception {
        log.info("Remuxing via ffmpeg ({} args)", cmd.size());
        ProcessExecutor.Result result =
                executor.run(cmd, options.ffmpegTimeoutSeconds(), TimeUnit.SECONDS);
        if (result == null) {
            throw new IOException("ffmpeg returned null result");
        }
        if (result.timedOut()) {
            throw new IOException("ffmpeg timed out after " + options.ffmpegTimeoutSeconds() + "s");
        }
        if (result.exitCode() == 0 && Files.exists(mp4Path) && Files.size(mp4Path) > 0) {
            log.info(
                    "Remux complete: {} ({} MB)",
                    mp4Path.getFileName(),
                    Files.size(mp4Path) / (1024 * 1024));
            return mp4Path;
        }
        if (fallbackPath != null && Files.exists(fallbackPath) && Files.size(fallbackPath) > 0) {
            log.warn(
                    "ffmpeg failed (exit={}), returning input fallback. snippet={}",
                    result.exitCode(),
                    result.stdoutSnippet());
            return fallbackPath;
        }
        throw new IOException(
                "ffmpeg failed (exit=" + result.exitCode() + ") and no fallback available");
    }

    private static String safeScheme(String url) {
        int colon = url.indexOf(':');
        return colon > 0 ? url.substring(0, Math.min(colon, 16)) : "(none)";
    }

    private static boolean containsControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    /** Tunable ffmpeg options. */
    public record RemuxOptions(String ffmpegBinary, int ffmpegTimeoutSeconds) {
        public static RemuxOptions defaults() {
            return new RemuxOptions("ffmpeg", 600);
        }
    }
}
