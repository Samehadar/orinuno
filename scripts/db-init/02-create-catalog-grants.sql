-- ADR 0018 Phase 5.9 — DB user separation for the shared catalog schema.
--
-- meter is the single writer of catalog_* (per Phase 5.2/5.3). orinuno is the
-- many-reader (Phase 5.4 read-only DS + Phase 5.7 CatalogController). The split
-- is enforced at the application layer via the @Repository contract, but we
-- also encode it at the MySQL grants level so an accidental write from orinuno
-- code (or an injected query) fails fast with a permission error instead of
-- silently corrupting catalog state.
--
-- These users live alongside the default `root` user the dev compose stack
-- already provisions. Production deploys are expected to override
-- DB_USERNAME / DB_PASSWORD per service:
--
--   meter:        orinuno_meter_writer  (CRUD on catalog_*)
--   orinuno-app:  orinuno_catalog_reader (SELECT-only on catalog_*)
--
-- Idempotent: CREATE USER IF NOT EXISTS + GRANT. Safe to re-run.

CREATE USER IF NOT EXISTS 'orinuno_meter_writer'@'%' IDENTIFIED BY 'meter_writer_pw';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES
    ON `orinuno_catalog`.* TO 'orinuno_meter_writer'@'%';

CREATE USER IF NOT EXISTS 'orinuno_catalog_reader'@'%' IDENTIFIED BY 'reader_pw';
GRANT SELECT ON `orinuno_catalog`.* TO 'orinuno_catalog_reader'@'%';

FLUSH PRIVILEGES;
