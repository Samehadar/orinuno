package com.orinuno.cvh.downloader.ffmpeg;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** Indirection over {@link Process} so the ffmpeg invocation is testable. */
public interface ProcessExecutor {

    Result run(List<String> command, long timeout, TimeUnit unit) throws Exception;

    record Result(int exitCode, boolean timedOut, String stdoutSnippet) {}

    static ProcessExecutor system() {
        return SystemProcessExecutor.INSTANCE;
    }

    @Slf4j
    final class SystemProcessExecutor implements ProcessExecutor {
        static final SystemProcessExecutor INSTANCE = new SystemProcessExecutor();

        private SystemProcessExecutor() {}

        @Override
        public Result run(List<String> command, long timeout, TimeUnit unit) throws Exception {
            ProcessBuilder pb = new ProcessBuilder(command);
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
        }
    }
}
