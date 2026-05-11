package com.orinuno.aksor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.aksor.model.AksorEpisode;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AksorEpisodeFilterTest {

    private static AksorEpisode episode(String number, String dubbing) {
        return new AksorEpisode(
                1L,
                number,
                dubbing,
                "Плеер Aksor",
                "248a4ad8181c6e5741371525d70e446b",
                "https://player.aksor.tv/video/248a4ad8181c6e5741371525d70e446b",
                1370,
                null,
                null,
                null);
    }

    @Test
    void allMatchesEverything() {
        AksorEpisodeFilter f = AksorEpisodeFilter.all();
        assertThat(f.isAll()).isTrue();
        assertThat(f.matches(episode("1", "AniLibria"))).isTrue();
        assertThat(f.matches(episode(null, null))).isTrue();
    }

    @Test
    void byNumberMatchesExact() {
        AksorEpisodeFilter f = AksorEpisodeFilter.byNumber("3");
        assertThat(f.isAll()).isFalse();
        assertThat(f.matches(episode("3", "any"))).isTrue();
        assertThat(f.matches(episode("4", "any"))).isFalse();
        assertThat(f.matches(episode(null, "any"))).isFalse();
    }

    @Test
    void byNumbersMatchesAnyInSet() {
        AksorEpisodeFilter f = AksorEpisodeFilter.byNumbers(Set.of("2", "5", "7"));
        assertThat(f.matches(episode("5", "x"))).isTrue();
        assertThat(f.matches(episode("3", "x"))).isFalse();
    }

    @Test
    void byDubbingCaseInsensitiveSubstring() {
        AksorEpisodeFilter f = AksorEpisodeFilter.byDubbing("anilibria");
        assertThat(f.matches(episode("1", "Озвучка AniLibria"))).isTrue();
        assertThat(f.matches(episode("1", "AniStar"))).isFalse();
        assertThat(f.matches(episode("1", null))).isFalse();
    }

    @Test
    void andDubbingComposesLogicalAnd() {
        AksorEpisodeFilter f = AksorEpisodeFilter.byNumber("2").andDubbing("anistar");
        assertThat(f.matches(episode("2", "AniStar"))).isTrue();
        assertThat(f.matches(episode("2", "AniLibria"))).isFalse();
        assertThat(f.matches(episode("3", "AniStar"))).isFalse();
    }

    @Test
    void andNumbersComposesLogicalAnd() {
        AksorEpisodeFilter f =
                AksorEpisodeFilter.byDubbing("anilibria").andNumbers(Set.of("1", "2"));
        assertThat(f.matches(episode("1", "AniLibria"))).isTrue();
        assertThat(f.matches(episode("3", "AniLibria"))).isFalse();
        assertThat(f.matches(episode("1", "AniStar"))).isFalse();
    }

    @Test
    void blankInputsRejected() {
        assertThatThrownBy(() -> AksorEpisodeFilter.byNumber(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AksorEpisodeFilter.byNumber(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AksorEpisodeFilter.byNumbers(Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AksorEpisodeFilter.byDubbing("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullEpisodeRejected() {
        assertThat(AksorEpisodeFilter.all().matches(null)).isFalse();
    }
}
