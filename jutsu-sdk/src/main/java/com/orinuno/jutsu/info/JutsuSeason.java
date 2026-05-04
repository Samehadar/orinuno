package com.orinuno.jutsu.info;

import java.util.ArrayList;
import java.util.List;

/**
 * One season block on an anime info page. Single-season anime collapse into a single {@link
 * JutsuSeason} with {@link #index()} = 1 and the {@link #name()} taken from {@code <h1>} (since
 * there's no per-season {@code <h2>}).
 *
 * @param index 1-based season number
 * @param name human-readable name as rendered in the page chrome (e.g. {@code "1 сезон"})
 * @param episodes ordered episode list for this season; never null but may be empty for upcoming /
 *     placeholder seasons
 */
public record JutsuSeason(int index, String name, List<JutsuEpisodeListing> episodes) {

    public JutsuSeason {
        if (index < 1) throw new IllegalArgumentException("index must be ≥ 1: " + index);
        if (name == null) throw new IllegalArgumentException("name must not be null");
        episodes = episodes == null ? List.of() : List.copyOf(episodes);
    }

    public boolean isEmpty() {
        return episodes.isEmpty();
    }

    public int episodeCount() {
        return episodes.size();
    }

    /** Mutable builder, used by {@code JutsuAnimeInfoParser} while accumulating episodes. */
    public static final class Builder {
        private final int index;
        private String name;
        private final List<JutsuEpisodeListing> episodes = new ArrayList<>();

        public Builder(int index, String name) {
            this.index = index;
            this.name = name == null ? "" : name;
        }

        public Builder name(String name) {
            this.name = name == null ? "" : name;
            return this;
        }

        public Builder add(JutsuEpisodeListing episode) {
            if (episode != null) episodes.add(episode);
            return this;
        }

        public JutsuSeason build() {
            return new JutsuSeason(index, name, episodes);
        }
    }
}
