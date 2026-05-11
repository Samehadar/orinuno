package com.orinuno.aksor;

import com.orinuno.aksor.model.AksorEpisode;
import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Filter applied to {@link com.orinuno.aksor.model.AksorAnime#episodes()} before each surviving
 * episode is enriched with {@link com.orinuno.aksor.model.AksorVideoQualities}. The point is to
 * skip the per-episode {@code player.aksor.tv/api/video/{hash}} call for episodes the caller does
 * not care about — for a 720-episode anime, a {@link #byNumber(String)} filter cuts 720 API hits
 * down to 1.
 *
 * <p>The host's videos endpoint ({@code /api/anime/{id}/videos}) is still fetched in full — it is a
 * single call that returns the whole episode list with hashes, and we need it to know which hashes
 * to fetch.
 *
 * <p>Predicate semantics:
 *
 * <ul>
 *   <li>{@code numbers == null} — any episode number accepted.
 *   <li>{@code numbers} non-null — exact-match {@link AksorEpisode#number()} against the set.
 *   <li>{@code dubbingSubstring == null} — any dubbing accepted.
 *   <li>{@code dubbingSubstring} non-null — case-insensitive substring match on {@link
 *       AksorEpisode#dubbing()}; episode with {@code dubbing()} {@code null} is rejected.
 * </ul>
 *
 * <p>When both fields are set, both must match (logical AND).
 */
public record AksorEpisodeFilter(@Nullable Set<String> numbers, @Nullable String dubbingSubstring) {

    public AksorEpisodeFilter {
        if (numbers != null) {
            numbers = Set.copyOf(numbers);
        }
        if (dubbingSubstring != null && dubbingSubstring.isBlank()) {
            dubbingSubstring = null;
        }
    }

    public static AksorEpisodeFilter all() {
        return new AksorEpisodeFilter(null, null);
    }

    public static AksorEpisodeFilter byNumber(String number) {
        if (number == null || number.isBlank()) {
            throw new IllegalArgumentException("number must not be blank");
        }
        return new AksorEpisodeFilter(Set.of(number), null);
    }

    public static AksorEpisodeFilter byNumbers(Set<String> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            throw new IllegalArgumentException("numbers must be non-empty");
        }
        return new AksorEpisodeFilter(numbers, null);
    }

    public static AksorEpisodeFilter byDubbing(String dubbingSubstring) {
        if (dubbingSubstring == null || dubbingSubstring.isBlank()) {
            throw new IllegalArgumentException("dubbingSubstring must not be blank");
        }
        return new AksorEpisodeFilter(null, dubbingSubstring);
    }

    /** Combine the current filter with a dubbing constraint (logical AND). */
    public AksorEpisodeFilter andDubbing(String dubbingSubstring) {
        if (dubbingSubstring == null || dubbingSubstring.isBlank()) {
            throw new IllegalArgumentException("dubbingSubstring must not be blank");
        }
        return new AksorEpisodeFilter(this.numbers, dubbingSubstring);
    }

    /** Combine the current filter with an episode-number constraint (logical AND). */
    public AksorEpisodeFilter andNumbers(Set<String> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            throw new IllegalArgumentException("numbers must be non-empty");
        }
        return new AksorEpisodeFilter(numbers, this.dubbingSubstring);
    }

    public boolean isAll() {
        return numbers == null && dubbingSubstring == null;
    }

    public boolean matches(AksorEpisode episode) {
        if (episode == null) {
            return false;
        }
        if (numbers != null && (episode.number() == null || !numbers.contains(episode.number()))) {
            return false;
        }
        if (dubbingSubstring != null) {
            String d = episode.dubbing();
            if (d == null) {
                return false;
            }
            return d.toLowerCase().contains(dubbingSubstring.toLowerCase());
        }
        return true;
    }
}
