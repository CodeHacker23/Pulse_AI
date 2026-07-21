-- Опросы Telegram: тип контента + варианты + анонимность (false = видно, кто голосовал).

ALTER TABLE generated_posts ADD COLUMN IF NOT EXISTS content_type VARCHAR(16) NOT NULL DEFAULT 'TEXT';
ALTER TABLE generated_posts ADD COLUMN IF NOT EXISTS poll_options TEXT;
ALTER TABLE generated_posts ADD COLUMN IF NOT EXISTS poll_anonymous BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE scheduled_posts ADD COLUMN IF NOT EXISTS content_type VARCHAR(16) NOT NULL DEFAULT 'TEXT';
ALTER TABLE scheduled_posts ADD COLUMN IF NOT EXISTS poll_options TEXT;
ALTER TABLE scheduled_posts ADD COLUMN IF NOT EXISTS poll_anonymous BOOLEAN NOT NULL DEFAULT FALSE;
