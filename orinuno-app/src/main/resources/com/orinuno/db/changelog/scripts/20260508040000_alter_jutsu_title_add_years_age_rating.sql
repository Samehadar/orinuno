--liquibase formatted sql
--changeset orinuno:20260508040000_alter_jutsu_title_add_years_age_rating

-- ARCH-0016 P1b — surface the per-season air years and the Russian age-rating
-- classifier from jut.su's labelled info block ("Жанры: …<br>Темы: …<br>Годы
-- выпуска: …<br>Возрастной рейтинг: …") on the cache-first read path so the
-- demo card and any future canonical-catalog consumer can render the same
-- chrome the live page does, without paying a live-fetch on every hit.
--
-- years_csv: comma-joined integer years in the order jut.su lists them
--   (chronological per the upstream template). VARCHAR is enough — even the
--   longest-running franchises (Naruto, One Piece) max out under a dozen
--   distinct year tokens. NULL when the parser couldn't find the block (drift
--   fallback) or when the page legitimately doesn't list multi-year releases.
--
-- age_rating: short wire form ("0+", "6+", "12+", "16+", "18+"). NULL for
--   pages without the badge (rare). Stored as text, not enum, so a future
--   widening (e.g. "12-" / "PG-13") doesn't require an ALTER. The Java side
--   maps it through JutsuAgeRating.fromWire(...) which gracefully returns
--   empty on unknown values.

ALTER TABLE jutsu_title
    ADD COLUMN years_csv  VARCHAR(64) NULL AFTER year_bucket,
    ADD COLUMN age_rating VARCHAR(8)  NULL AFTER years_csv;
