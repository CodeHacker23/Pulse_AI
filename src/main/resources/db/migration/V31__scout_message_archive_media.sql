-- Медиа для архива ЛС скаутов (файлы на диске data/scout_media/).

ALTER TABLE scout_message_archive
    ADD COLUMN IF NOT EXISTS media_kind VARCHAR(16);

ALTER TABLE scout_message_archive
    ADD COLUMN IF NOT EXISTS media_path VARCHAR(512);

ALTER TABLE scout_message_archive
    ADD COLUMN IF NOT EXISTS media_mime VARCHAR(128);

ALTER TABLE scout_message_archive
    ADD COLUMN IF NOT EXISTS media_file_name VARCHAR(256);

ALTER TABLE scout_message_archive
    ADD COLUMN IF NOT EXISTS media_size BIGINT;
