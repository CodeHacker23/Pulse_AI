ALTER TABLE channels
    ADD COLUMN IF NOT EXISTS content_style_prompt TEXT;
