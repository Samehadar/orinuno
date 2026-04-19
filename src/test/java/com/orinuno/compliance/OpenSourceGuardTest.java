package com.orinuno.compliance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PF5: Ensures no proprietary references leak into open-source codebase.
 */
class OpenSourceGuardTest {

    private static final List<String> FORBIDDEN_STRINGS = List.of(
            "kinodostup",
            "backend-master",
            "com.kinodostup"
    );

    private static final Pattern FORBIDDEN_PATTERN = Pattern.compile(
            String.join("|", FORBIDDEN_STRINGS),
            Pattern.CASE_INSENSITIVE
    );

    @Test
    @DisplayName("PF5: Source code should not contain proprietary references")
    void sourceCodeShouldNotContainProprietaryReferences() throws IOException {
        Path srcDir = findProjectRoot().resolve("src");
        if (!Files.exists(srcDir)) {
            return; // skip if running from unexpected location
        }

        List<String> violations = new ArrayList<>();

        Files.walkFileTree(srcDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (fileName.endsWith(".java") || fileName.endsWith(".xml") || fileName.endsWith(".yml")
                        || fileName.endsWith(".yaml") || fileName.endsWith(".properties")) {

                    // Skip this test file itself
                    if (fileName.equals("OpenSourceGuardTest.java")) {
                        return FileVisitResult.CONTINUE;
                    }

                    String content = Files.readString(file);
                    var matcher = FORBIDDEN_PATTERN.matcher(content);
                    while (matcher.find()) {
                        violations.add(String.format("%s: found '%s' at position %d",
                                srcDir.relativize(file), matcher.group(), matcher.start()));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(violations)
                .as("Proprietary references found in source code")
                .isEmpty();
    }

    private Path findProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir"));
        // Walk up until we find pom.xml
        while (current != null && !Files.exists(current.resolve("pom.xml"))) {
            current = current.getParent();
        }
        return current != null ? current : Path.of(".");
    }
}
