package com.orinuno.service.dumps;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.client.dto.KodikSearchResponse;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;

/**
 * DUMP-2 — Streaming reader for the public Kodik dumps.
 *
 * <p>Each dump (calendar / serials / films) is a JSON array of objects whose shape mirrors {@link
 * KodikSearchResponse.Result} (with {@code @JsonIgnoreProperties(ignoreUnknown = true)} absorbing
 * any new fields Kodik may add). The reader uses Jackson's pull parser to walk the array
 * one-element-at-a-time so that even {@code films.json} (~82 MB) and {@code serials.json} (~175 MB)
 * can be ingested with bounded memory.
 *
 * <p>The {@code InputStream} is consumed but NOT closed by the reader — caller owns the stream
 * lifecycle so they can wrap it with progress tracking, decompression, or rate limiting.
 *
 * <p>Per-element parse failures are logged and skipped (counted in the returned {@link
 * StreamingResult}); they do not abort the loop. This is deliberate: DUMP entries occasionally
 * contain shapes the SDK doesn't model (e.g. a non-Map {@code translation}), and we'd rather ingest
 * 99.9% of the catalogue than fail on the 0.1% edge case.
 */
@Slf4j
public class KodikDumpStreamingReader {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private KodikDumpStreamingReader() {}

    /**
     * Stream every element of a JSON array dump, invoking {@code consumer} for each successfully
     * parsed {@link KodikSearchResponse.Result}. Items that fail to deserialise are logged and
     * counted as {@code skipped} but do not abort the stream.
     *
     * @throws IOException on a structural failure (not a JSON array, premature EOF inside an
     *     element). Per-element parse errors are NOT thrown.
     */
    public static StreamingResult readArray(
            InputStream stream, Consumer<KodikSearchResponse.Result> consumer) throws IOException {
        long processed = 0;
        long skipped = 0;
        try (JsonParser parser = JSON_FACTORY.createParser(stream)) {
            JsonToken first = parser.nextToken();
            if (first != JsonToken.START_ARRAY) {
                throw new IOException(
                        "Expected JSON array at root, got " + first + " — malformed dump?");
            }
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                try {
                    KodikSearchResponse.Result result =
                            OBJECT_MAPPER.readValue(parser, KodikSearchResponse.Result.class);
                    consumer.accept(result);
                    processed++;
                } catch (IOException ioEx) {
                    skipped++;
                    log.warn(
                            "DUMP-2: skipping malformed dump entry #{}: {}",
                            processed + skipped,
                            ioEx.toString());
                    skipToNextElement(parser);
                } catch (RuntimeException ex) {
                    skipped++;
                    log.warn(
                            "DUMP-2: consumer rejected dump entry #{}: {}",
                            processed + skipped,
                            ex.toString());
                }
            }
        }
        return new StreamingResult(processed, skipped);
    }

    private static void skipToNextElement(JsonParser parser) throws IOException {
        JsonToken current = parser.currentToken();
        if (current == JsonToken.START_OBJECT || current == JsonToken.START_ARRAY) {
            parser.skipChildren();
        }
    }

    /** Counts of items the reader saw. Useful for the bootstrap report. */
    public record StreamingResult(long processed, long skipped) {
        public long total() {
            return processed + skipped;
        }
    }
}
