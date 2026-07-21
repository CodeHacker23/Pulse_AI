-- Апгрейд лид-агента: черновик ответа (approve), CRM-статусы лида, база ответов канала (FAQ/прайс).

ALTER TABLE hot_leads ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'NEW';
ALTER TABLE hot_leads ADD COLUMN IF NOT EXISTS suggested_reply TEXT;

-- База ответов админа: цены, доставка, оффер — питает черновики ответов агента.
ALTER TABLE channels ADD COLUMN IF NOT EXISTS sales_faq TEXT;

CREATE INDEX IF NOT EXISTS idx_hot_leads_status ON hot_leads (owner_user_id, status);
