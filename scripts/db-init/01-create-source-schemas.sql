-- docker-entrypoint-initdb.d bootstrap for the shared MySQL container.
--
-- MySQL's MYSQL_DATABASE env only creates a single default schema (`orinuno`,
-- used by orinuno-app). ADR 0018 Phase 2 adds the standalone source services,
-- each owning its own schema in the same container so dev/CI can run the full
-- per-source stack without juggling multiple MySQL instances.
--
-- Production deployments are expected to provision real DB instances and
-- override DB_HOST/DB_NAME per service — this file only matters for the
-- bundled docker-compose stack.

CREATE DATABASE IF NOT EXISTS `orinuno_source_kodik`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- ADR 0018 Phase 5.2a — shared catalog schema owned (write-side) by `meter`.
-- orinuno-app currently keeps a parallel copy of catalog_* in the orinuno
-- schema; Phase 5.4 cuts the read-path over to this shared schema, Phase 5.6
-- drops the parallel copy from the orinuno schema.
CREATE DATABASE IF NOT EXISTS `orinuno_catalog`
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- Reserved for Phase 4 — kept here so the schema list is grep-discoverable.
-- CREATE DATABASE IF NOT EXISTS `orinuno_source_jutsu`
--     CHARACTER SET utf8mb4
--     COLLATE utf8mb4_unicode_ci;
