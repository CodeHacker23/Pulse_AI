-- Фото к сгенерированным постам (подбор через Pexels).
ALTER TABLE generated_posts ADD COLUMN IF NOT EXISTS image_url VARCHAR(1024);
