-- Карантин после FLOOD: статус FLOOD_WAIT до quarantine_until, потом авто-ACTIVE/WARMING.
ALTER TABLE scout_accounts
    ADD COLUMN IF NOT EXISTS quarantine_until TIMESTAMP WITH TIME ZONE;
