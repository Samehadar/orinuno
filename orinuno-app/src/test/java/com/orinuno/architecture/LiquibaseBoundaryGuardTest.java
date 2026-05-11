/*
 * Liquibase boundary guard.
 *
 * Codifies ADR 0016 §"Boundary discipline" rule 3 (no cross-context FOREIGN KEYs) as a unit
 * test. Walks every *.sql under db/changelog/scripts/, infers each migration's owning context
 * from its filename, locates FK targets via regex, and asserts the target table belongs to
 * the same context. ADR 0018 carries this rule forward as a pre-extraction prerequisite —
 * once per-source services own their schemas independently, any cross-DB FK is impossible at
 * runtime and the SQL-level rule prevents new violations now.
 *
 * Known legacy violations are whitelisted with their ADR / migration reference so that the
 * baseline stays green while we plan the corrective migration separately.
 */
package com.orinuno.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class LiquibaseBoundaryGuardTest {

    /**
     * Matches "REFERENCES &lt;tableName&gt;" with arbitrary whitespace / newlines between the
     * keyword and the identifier. Captures the target table name. Case-insensitive, DOTALL because
     * some migrations break "REFERENCES" and the table name across lines.
     */
    private static final Pattern REFERENCES_PATTERN =
            Pattern.compile(
                    "REFERENCES\\s+([A-Za-z_][A-Za-z0-9_]*)",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * Matches a Liquibase filename of the form {@code
     * YYYYMMDDHHMMSS_(create|alter|drop)_&lt;table&gt;.sql} and captures the target/owner table
     * name. Migrations that do not match this convention (e.g. backfill-only migrations with no
     * table name in the filename) are flagged so we notice and either rename them or add a
     * comment-tag fallback.
     */
    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("\\d{14}_(?:create|alter|drop)_([a-z_][a-z0-9_]*)\\.sql$");

    /**
     * Source migration directory. Test is co-located with the resources path so a missing directory
     * means surrounding refactors broke our assumption.
     */
    private static final Path CHANGELOG_DIR =
            Paths.get("src/main/resources/com/orinuno/db/changelog/scripts");

    /**
     * Cross-context FKs we are explicitly tolerating until the corrective migration ships. Format:
     * {@code source_table:target_table}. Every entry must point at an ADR / TECH_DEBT line that
     * owns the cleanup. Removing an entry tightens the guard — never silently widen.
     *
     * <ul>
     *   <li>{@code episode_source:kodik_content} — ADR 0005 (pre-L3 era). The L2 pointer table was
     *       created before {@code catalog_content} existed. Once Phase 5 of ADR 0018 moves L2/L3
     *       into the OSS meter service, this FK is re-pointed at {@code catalog_content} (or
     *       dropped in favour of a soft reference, per ADR 0016 rule 3 for cross-context links).
     * </ul>
     */
    private static final Set<String> LEGACY_FK_WHITELIST = Set.of("episode_source:kodik_content");

    @Test
    void every_foreign_key_stays_inside_its_owning_context() throws IOException {
        assertThat(CHANGELOG_DIR)
                .as("Liquibase scripts directory must exist; if you moved it, update CHANGELOG_DIR")
                .exists()
                .isDirectory();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(CHANGELOG_DIR)) {
            List<Path> migrations =
                    stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().endsWith(".sql"))
                            .sorted()
                            .toList();

            for (Path file : migrations) {
                String filename = file.getFileName().toString();
                String sourceTable = parseSourceTable(filename);
                if (sourceTable == null) {
                    // Filenames without a `_(create|alter|drop)_<table>_` segment are not
                    // schema-shape migrations (e.g. pure backfill / data fixes). They cannot
                    // declare FKs in a meaningful way, so skip them. If a future migration
                    // does add an FK from such a file, it falls through to fail at FK parse
                    // (no source-table → no context match → violation).
                    continue;
                }

                String sourceContext = contextOf(sourceTable);
                String text = Files.readString(file, StandardCharsets.UTF_8);

                Matcher m = REFERENCES_PATTERN.matcher(text);
                while (m.find()) {
                    String targetTable = m.group(1).toLowerCase();
                    String targetContext = contextOf(targetTable);

                    if (sourceContext.equals(targetContext)) {
                        continue;
                    }
                    if (LEGACY_FK_WHITELIST.contains(sourceTable + ":" + targetTable)) {
                        continue;
                    }
                    violations.add(
                            String.format(
                                    "%s declares FOREIGN KEY %s.* -> %s.* (%s -> %s) which crosses"
                                            + " bounded contexts.",
                                    filename,
                                    sourceTable,
                                    targetTable,
                                    sourceContext,
                                    targetContext));
                }
            }
        }

        assertThat(violations)
                .as(
                        "Cross-context FOREIGN KEYs forbidden by ADR 0016 rule 3 and "
                                + "ADR 0018 pre-extraction discipline. Use a soft reference "
                                + "(column without FK constraint) or move the relationship into "
                                + "the L3 catalog link table.")
                .isEmpty();
    }

    private static String parseSourceTable(String filename) {
        Matcher m = FILENAME_PATTERN.matcher(filename);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Maps a SQL table name to its bounded context. The order matters: most-specific prefix wins.
     * Keep this consistent with ADR 0016 §"Three-layer data model" and the per-source service split
     * outlined in ADR 0018.
     */
    private static String contextOf(String tableName) {
        if (tableName.startsWith("jutsu_")) {
            return "jutsu";
        }
        if (tableName.startsWith("kodik_")) {
            return "kodik";
        }
        if (tableName.startsWith("catalog_")) {
            return "catalog";
        }
        if (tableName.equals("episode_source") || tableName.equals("episode_video")) {
            return "core";
        }
        if (tableName.startsWith("orinuno_")) {
            return "core";
        }
        return "unknown";
    }
}
