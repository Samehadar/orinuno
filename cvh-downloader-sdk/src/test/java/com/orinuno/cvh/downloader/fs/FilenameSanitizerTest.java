package com.orinuno.cvh.downloader.fs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FilenameSanitizerTest {

    @Test
    void stripsControlChars() {
        assertThat(FilenameSanitizer.sanitize("foo\r\nbar\tbaz")).isEqualTo("foobarbaz");
    }

    @Test
    void replacesForbiddenChars() {
        assertThat(FilenameSanitizer.sanitize("a/b\\c:d*e?f\"g<h>i|j"))
                .isEqualTo("a_b_c_d_e_f_g_h_i_j");
    }

    @Test
    void stripsLeadingDots() {
        assertThat(FilenameSanitizer.sanitize("...secret")).isEqualTo("secret");
        assertThat(FilenameSanitizer.sanitize("..")).isEqualTo("video");
    }

    @Test
    void capsLength() {
        String huge = "x".repeat(500);
        assertThat(FilenameSanitizer.sanitize(huge)).hasSize(180);
    }

    @Test
    void nullAndBlankYieldFallback() {
        assertThat(FilenameSanitizer.sanitize(null)).isEqualTo("video");
        assertThat(FilenameSanitizer.sanitize("")).isEqualTo("video");
        assertThat(FilenameSanitizer.sanitize("   ")).isEqualTo("video");
    }

    @Test
    void traversalAttemptCleaned() {
        // Slashes become underscores so the path is no longer a multi-segment traversal;
        // LocalFsDestination.resolveSafe is the second line of defense and rejects what slips
        // through here.
        String cleaned = FilenameSanitizer.sanitize("../../etc/passwd");
        assertThat(cleaned).doesNotContain("/").doesNotContain("\\");
        assertThat(cleaned).contains("etc").contains("passwd");
    }
}
