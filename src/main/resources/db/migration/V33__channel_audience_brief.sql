ALTER TABLE channels
    ADD COLUMN IF NOT EXISTS audience_brief TEXT;
