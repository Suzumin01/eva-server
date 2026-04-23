-- ============================================================
-- ЕВА — Flyway Migration V2
-- Колонки флагов напоминаний уже включены в V1.
-- Этот файл зарезервирован — применяется как no-op,
-- чтобы не нарушать нумерацию при накатке на существующую БД,
-- где appointments была создана без reminder-колонок.
-- ============================================================

ALTER TABLE appointments
    ADD COLUMN IF NOT EXISTS reminder_24h_sent BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reminder_1h_sent  BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_appointments_reminders
    ON appointments (status, reminder_24h_sent, reminder_1h_sent)
    WHERE status = 'scheduled';
