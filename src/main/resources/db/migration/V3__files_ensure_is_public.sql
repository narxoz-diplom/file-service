-- Idempotent: если история Flyway «уехала» от реальной схемы (бэкап БД без колонки).
ALTER TABLE files
    ADD COLUMN IF NOT EXISTS is_public boolean NOT NULL DEFAULT false;
