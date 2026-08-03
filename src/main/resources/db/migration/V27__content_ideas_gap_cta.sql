-- Ideas: closes_gap + cta from improved generation prompt
ALTER TABLE content_ideas ADD COLUMN IF NOT EXISTS closes_gap VARCHAR(256);
ALTER TABLE content_ideas ADD COLUMN IF NOT EXISTS cta VARCHAR(512);
