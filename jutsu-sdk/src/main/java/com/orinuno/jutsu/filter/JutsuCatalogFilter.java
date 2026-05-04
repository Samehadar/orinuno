package com.orinuno.jutsu.filter;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Immutable description of a catalog filter selection. Captures the four orthogonal facets exposed
 * by jut.su's filter form — genres, types, years, sort — and intentionally nothing else.
 *
 * <p>The website's frontend builds the URL slug in <em>click order</em>, but the backend treats
 * each segment as a set, so different orderings yield identical results. {@link JutsuFilterSlugger}
 * fixes one canonical ordering (enum-declaration) so two callers asking for the same logical filter
 * generate the same URL — important for caching, logging, and idempotent scheduled jobs.
 *
 * <p>Use the builder for ergonomic construction:
 *
 * <pre>{@code
 * JutsuCatalogFilter f = JutsuCatalogFilter.builder()
 *         .addGenre(JutsuGenre.COMEDY)
 *         .addGenre(JutsuGenre.ROMANCE)
 *         .addType(JutsuType.SHOJO)
 *         .addYear(JutsuYear.Y_2024)
 *         .sort(JutsuSort.BY_DATE_ADDED)
 *         .build();
 * String path = JutsuFilterSlugger.composePath(f);
 * // → /anime/comedy-romance-shojo/2024/order-by-add/
 * }</pre>
 *
 * <p>Equality is based on the (set, set, set, sort) tuple, regardless of insertion order.
 */
public final class JutsuCatalogFilter {

    private static final JutsuCatalogFilter EMPTY = builder().build();

    private final Set<JutsuGenre> genres;
    private final Set<JutsuType> types;
    private final Set<JutsuYear> years;
    private final JutsuSort sort;

    private JutsuCatalogFilter(
            Set<JutsuGenre> genres, Set<JutsuType> types, Set<JutsuYear> years, JutsuSort sort) {
        this.genres =
                Collections.unmodifiableSet(EnumSet.copyOf(toIterable(genres, JutsuGenre.class)));
        this.types =
                Collections.unmodifiableSet(EnumSet.copyOf(toIterable(types, JutsuType.class)));
        this.years =
                Collections.unmodifiableSet(EnumSet.copyOf(toIterable(years, JutsuYear.class)));
        this.sort = sort;
    }

    /**
     * The empty filter — equivalent to GET /anime/ with default sort. Useful as a baseline for
     * builder().mergeFrom(...) flows.
     */
    public static JutsuCatalogFilter empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<JutsuGenre> genres() {
        return genres;
    }

    public Set<JutsuType> types() {
        return types;
    }

    public Set<JutsuYear> years() {
        return years;
    }

    public JutsuSort sort() {
        return sort;
    }

    /** {@code true} when this filter would generate the same URL as the bare {@code /anime/}. */
    public boolean isEmpty() {
        return genres.isEmpty()
                && types.isEmpty()
                && years.isEmpty()
                && sort == JutsuSort.BY_RATING;
    }

    /** Return a builder pre-loaded with this filter's selections. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.genres.addAll(genres);
        builder.types.addAll(types);
        builder.years.addAll(years);
        builder.sort = sort;
        return builder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JutsuCatalogFilter other)) return false;
        return genres.equals(other.genres)
                && types.equals(other.types)
                && years.equals(other.years)
                && sort == other.sort;
    }

    @Override
    public int hashCode() {
        int h = genres.hashCode();
        h = 31 * h + types.hashCode();
        h = 31 * h + years.hashCode();
        h = 31 * h + sort.hashCode();
        return h;
    }

    @Override
    public String toString() {
        return "JutsuCatalogFilter{genres="
                + genres
                + ", types="
                + types
                + ", years="
                + years
                + ", sort="
                + sort
                + '}';
    }

    /**
     * Returns an EnumSet of the requested type, copying contents from {@code source}. Empty source
     * yields an empty EnumSet (the canonical way to build empty EnumSets, since {@code
     * EnumSet.copyOf} of an empty collection requires the runtime class).
     */
    @SuppressWarnings("unchecked")
    private static <E extends Enum<E>> EnumSet<E> toIterable(Set<E> source, Class<E> klass) {
        if (source == null || source.isEmpty()) return EnumSet.noneOf(klass);
        if (source instanceof EnumSet<?>) {
            return (EnumSet<E>) source;
        }
        return EnumSet.copyOf(source);
    }

    /**
     * Mutable builder for {@link JutsuCatalogFilter}. Defaults: empty genre/type/year sets, sort
     * set to {@link JutsuSort#BY_RATING} (the website default).
     */
    public static final class Builder {
        private final EnumSet<JutsuGenre> genres = EnumSet.noneOf(JutsuGenre.class);
        private final EnumSet<JutsuType> types = EnumSet.noneOf(JutsuType.class);
        private final EnumSet<JutsuYear> years = EnumSet.noneOf(JutsuYear.class);
        private JutsuSort sort = JutsuSort.BY_RATING;

        private Builder() {}

        public Builder addGenre(JutsuGenre genre) {
            if (genre == null) throw new IllegalArgumentException("genre must not be null");
            genres.add(genre);
            return this;
        }

        public Builder addGenres(Collection<JutsuGenre> values) {
            if (values == null) return this;
            for (JutsuGenre g : values) addGenre(g);
            return this;
        }

        public Builder addType(JutsuType type) {
            if (type == null) throw new IllegalArgumentException("type must not be null");
            types.add(type);
            return this;
        }

        public Builder addTypes(Collection<JutsuType> values) {
            if (values == null) return this;
            for (JutsuType t : values) addType(t);
            return this;
        }

        public Builder addYear(JutsuYear year) {
            if (year == null) throw new IllegalArgumentException("year must not be null");
            years.add(year);
            return this;
        }

        public Builder addYears(Collection<JutsuYear> values) {
            if (values == null) return this;
            for (JutsuYear y : values) addYear(y);
            return this;
        }

        public Builder sort(JutsuSort sort) {
            this.sort = sort == null ? JutsuSort.BY_RATING : sort;
            return this;
        }

        public Builder clearGenres() {
            genres.clear();
            return this;
        }

        public Builder clearTypes() {
            types.clear();
            return this;
        }

        public Builder clearYears() {
            years.clear();
            return this;
        }

        public JutsuCatalogFilter build() {
            return new JutsuCatalogFilter(genres, types, years, sort);
        }
    }
}
