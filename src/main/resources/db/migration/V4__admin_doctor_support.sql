-- V4: admin/doctor support
-- Добавляет роль doctor, привязку doctor→user, мягкое скрытие отзывов, индексы для admin-запросов

INSERT INTO roles (role_name) VALUES ('doctor') ON CONFLICT (role_name) DO NOTHING;

ALTER TABLE doctors ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(user_id) ON DELETE SET NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_doctors_user_id ON doctors(user_id) WHERE user_id IS NOT NULL;

ALTER TABLE doctor_reviews ADD COLUMN IF NOT EXISTS is_hidden BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_appointments_status  ON appointments(status);
CREATE INDEX IF NOT EXISTS idx_appointments_doctor  ON appointments(doctor_id);
CREATE INDEX IF NOT EXISTS idx_appointments_created ON appointments(created_at DESC);
