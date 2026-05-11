--liquibase formatted sql
--changeset orinuno:20260508020000_alter_jutsu_title_add_catalog_position

-- ARCH-0016 P1a Step 3.A — record each title's 1-based position in the last full
-- catalog crawl so the read side can reproduce jut.su's default "by rating" sort
-- without a separate ranking column. catalog_position = (page - 1) * 30 + slot,
-- where (page, slot) is the 1-based coordinate of the entry in the JutsuCatalogPage
-- stream. NULL means the entry has not been observed by a full crawl yet (notice-
-- walk placeholders, fresh DB) — the read service treats NULL as "rank unknown,
-- sort to the bottom" so a partially-warmed cache still produces sensible orderings.

ALTER TABLE jutsu_title
    ADD COLUMN catalog_position INT NULL AFTER catalog_movie_count,
    ADD KEY idx_jutsu_title_catalog_position (catalog_position);
