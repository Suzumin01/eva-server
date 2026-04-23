-- ============================================================
-- ЕВА — E-Health Virtual Assistant
-- Flyway Migration V1 — полная начальная схема
-- PostgreSQL 15+  |  Ktor + Exposed ORM
-- Автор: Смольников Никита Сергеевич, ИКБО-35-22, РТУ МИРЭА
-- ============================================================

-- ============================================================
-- 0. EXTENSIONS
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "pg_trgm";
CREATE EXTENSION IF NOT EXISTS "unaccent";

-- ============================================================
-- 1. ENUM TYPES
-- ============================================================

CREATE TYPE appointment_status AS ENUM (
    'scheduled',
    'cancelled',
    'completed'
);

CREATE TYPE notification_channel AS ENUM (
    'push',
    'email',
    'sms'
);

CREATE TYPE urgency_level AS ENUM (
    'low',
    'normal',
    'urgent',
    'emergency'
);

-- ============================================================
-- 2. ВСПОМОГАТЕЛЬНАЯ ФУНКЦИЯ — авто-обновление updated_at
-- ============================================================

CREATE OR REPLACE FUNCTION trigger_set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================
-- 3. TABLE: roles
-- ============================================================

CREATE TABLE roles (
    role_id    SMALLINT     PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    role_name  VARCHAR(50)  NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trg_roles_updated_at
    BEFORE UPDATE ON roles
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 4. TABLE: users
-- ============================================================

CREATE TABLE users (
    user_id          UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    full_name        VARCHAR(255) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    phone            VARCHAR(20),
    password_hash    VARCHAR(255) NOT NULL,
    role_id          SMALLINT     NOT NULL REFERENCES roles(role_id) ON UPDATE CASCADE,
    -- Профиль
    date_of_birth    DATE,
    avatar_url       VARCHAR(500),
    -- Медицинские данные пациента
    allergies        TEXT,
    chronic_diseases TEXT,
    insurance_policy VARCHAR(100),
    -- Согласия (ФЗ-152 «О персональных данных»)
    consent_medical  BOOLEAN      NOT NULL DEFAULT FALSE,
    consent_ai       BOOLEAN      NOT NULL DEFAULT FALSE,
    consent_at       TIMESTAMPTZ,
    -- Soft delete
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    last_login_at    TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_users_email  UNIQUE (email),
    CONSTRAINT uq_users_phone  UNIQUE (phone),
    CONSTRAINT chk_users_email CHECK (
        email ~* '^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$'
    ),
    CONSTRAINT chk_users_phone CHECK (
        phone IS NULL OR phone ~ '^\+?[0-9]{7,15}$'
    ),
    CONSTRAINT chk_users_name CHECK (char_length(full_name) >= 2),
    CONSTRAINT chk_users_dob  CHECK (
        date_of_birth IS NULL OR date_of_birth < CURRENT_DATE
    ),
    CONSTRAINT chk_users_consent CHECK (
        NOT (consent_ai = TRUE AND consent_medical = FALSE)
    )
);

CREATE UNIQUE INDEX uix_users_email_lower ON users (lower(email));
CREATE INDEX idx_users_phone             ON users (phone)     WHERE phone IS NOT NULL;
CREATE INDEX idx_users_role_id           ON users (role_id);
CREATE INDEX idx_users_is_active         ON users (is_active) WHERE is_active = TRUE;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 5. TABLE: refresh_tokens
-- ============================================================

CREATE TABLE refresh_tokens (
    token_id   UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id    UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token      VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ  NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_tokens_user    ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_token   ON refresh_tokens (token)      WHERE revoked = FALSE;
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at) WHERE revoked = FALSE;

-- ============================================================
-- 6. TABLE: fcm_tokens
-- ============================================================

CREATE TABLE fcm_tokens (
    token_id   BIGINT       PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    user_id    UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    token      TEXT         NOT NULL UNIQUE,
    device_id  VARCHAR(255),
    platform   VARCHAR(10)  NOT NULL DEFAULT 'android'
        CHECK (platform IN ('android', 'ios', 'web')),
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_fcm_tokens_user ON fcm_tokens (user_id) WHERE is_active = TRUE;

CREATE TRIGGER trg_fcm_tokens_updated_at
    BEFORE UPDATE ON fcm_tokens
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 6. TABLE: clinics
-- ============================================================

CREATE TABLE clinics (
    clinic_id   SERIAL       PRIMARY KEY,
    clinic_name VARCHAR(255) NOT NULL,
    address     VARCHAR(500) NOT NULL,
    phone       VARCHAR(20),
    website     VARCHAR(255),
    latitude    NUMERIC(10,7),
    longitude   NUMERIC(10,7),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_clinics_name      CHECK (char_length(clinic_name) >= 2),
    CONSTRAINT chk_clinics_latitude  CHECK (latitude  IS NULL OR latitude  BETWEEN -90  AND 90),
    CONSTRAINT chk_clinics_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180)
);

CREATE INDEX idx_clinics_active   ON clinics (is_active) WHERE is_active = TRUE;
CREATE INDEX idx_clinics_name_gin ON clinics USING GIN (to_tsvector('russian', clinic_name));

CREATE TRIGGER trg_clinics_updated_at
    BEFORE UPDATE ON clinics
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 7. TABLE: specializations
-- ============================================================

CREATE TABLE specializations (
    specialization_id SMALLINT     PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    name              VARCHAR(150) NOT NULL UNIQUE,
    description       TEXT,
    icon_url          VARCHAR(500),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_specializations_name_trgm ON specializations USING GIN (name gin_trgm_ops);

CREATE TRIGGER trg_specializations_updated_at
    BEFORE UPDATE ON specializations
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 8. TABLE: doctors
-- ============================================================

CREATE TABLE doctors (
    doctor_id         SERIAL       PRIMARY KEY,
    full_name         VARCHAR(255) NOT NULL,
    clinic_id         INTEGER      NOT NULL REFERENCES clinics(clinic_id)           ON UPDATE CASCADE,
    specialization_id SMALLINT     NOT NULL REFERENCES specializations(specialization_id) ON UPDATE CASCADE,
    bio               TEXT,
    photo_url         VARCHAR(500),
    experience_years  SMALLINT,
    rating            NUMERIC(3,2),
    reviews_count     INTEGER      NOT NULL DEFAULT 0,
    search_vector     TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('russian', coalesce(full_name, '') || ' ' || coalesce(bio, ''))
    ) STORED,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_doctors_rating     CHECK (rating IS NULL OR rating BETWEEN 0 AND 5),
    CONSTRAINT chk_doctors_name       CHECK (char_length(full_name) >= 2),
    CONSTRAINT chk_doctors_experience CHECK (experience_years IS NULL OR experience_years >= 0),
    CONSTRAINT chk_doctors_reviews    CHECK (reviews_count >= 0)
);

CREATE INDEX idx_doctors_spec_clinic ON doctors (specialization_id, clinic_id) WHERE is_active = TRUE;
CREATE INDEX idx_doctors_rating      ON doctors (rating DESC NULLS LAST)        WHERE is_active = TRUE;
CREATE INDEX idx_doctors_fts         ON doctors USING GIN (search_vector);
CREATE INDEX idx_doctors_name_trgm   ON doctors USING GIN (full_name gin_trgm_ops);

CREATE TRIGGER trg_doctors_updated_at
    BEFORE UPDATE ON doctors
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 9. TABLE: doctor_reviews
-- ============================================================

CREATE TABLE doctor_reviews (
    review_id  UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    doctor_id  INTEGER      NOT NULL REFERENCES doctors(doctor_id) ON UPDATE CASCADE,
    user_id    UUID         NOT NULL REFERENCES users(user_id)     ON UPDATE CASCADE,
    rating     SMALLINT     NOT NULL,
    comment    TEXT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_review_user_doctor UNIQUE (doctor_id, user_id),
    CONSTRAINT chk_review_rating     CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT chk_review_comment    CHECK (comment IS NULL OR char_length(comment) <= 2000)
);

CREATE INDEX idx_doctor_reviews_doctor ON doctor_reviews (doctor_id);
CREATE INDEX idx_doctor_reviews_user   ON doctor_reviews (user_id);

CREATE TRIGGER trg_doctor_reviews_updated_at
    BEFORE UPDATE ON doctor_reviews
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE OR REPLACE FUNCTION trg_recalc_doctor_rating()
RETURNS TRIGGER AS $$
DECLARE
    v_doctor_id INTEGER;
BEGIN
    v_doctor_id := COALESCE(NEW.doctor_id, OLD.doctor_id);
    UPDATE doctors SET
        rating        = (
            SELECT ROUND(AVG(rating)::numeric, 2)
            FROM doctor_reviews
            WHERE doctor_id = v_doctor_id
        ),
        reviews_count = (
            SELECT COUNT(*)
            FROM doctor_reviews
            WHERE doctor_id = v_doctor_id
        )
    WHERE doctor_id = v_doctor_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_doctor_reviews_rating
    AFTER INSERT OR UPDATE OR DELETE ON doctor_reviews
    FOR EACH ROW EXECUTE FUNCTION trg_recalc_doctor_rating();

-- ============================================================
-- 10. TABLE: schedules
-- ============================================================

CREATE TABLE schedules (
    schedule_id      BIGINT      PRIMARY KEY GENERATED ALWAYS AS IDENTITY,
    doctor_id        INTEGER     NOT NULL REFERENCES doctors(doctor_id) ON UPDATE CASCADE,
    slot_date        DATE        NOT NULL,
    slot_time        TIME        NOT NULL,
    duration_minutes SMALLINT    NOT NULL DEFAULT 30,
    is_available     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_schedules_doctor_slot UNIQUE (doctor_id, slot_date, slot_time),
    CONSTRAINT chk_schedules_duration   CHECK (duration_minutes BETWEEN 5 AND 180),
    CONSTRAINT chk_schedules_date       CHECK (slot_date >= '2020-01-01'),
    CONSTRAINT chk_schedules_time       CHECK (slot_time BETWEEN '07:00' AND '22:00')
);

CREATE INDEX idx_schedules_doctor_date ON schedules (doctor_id, slot_date) WHERE is_available = TRUE;
CREATE INDEX idx_schedules_date        ON schedules (slot_date)            WHERE is_available = TRUE;
CREATE INDEX idx_schedules_doctor      ON schedules (doctor_id);

CREATE TRIGGER trg_schedules_updated_at
    BEFORE UPDATE ON schedules
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

-- ============================================================
-- 11. TABLE: appointments
-- ============================================================

CREATE TABLE appointments (
    appointment_id    UUID               PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id           UUID               NOT NULL REFERENCES users(user_id)         ON UPDATE CASCADE,
    doctor_id         INTEGER            NOT NULL REFERENCES doctors(doctor_id)     ON UPDATE CASCADE,
    schedule_id       BIGINT             NOT NULL REFERENCES schedules(schedule_id) ON UPDATE CASCADE,
    status            appointment_status NOT NULL DEFAULT 'scheduled',
    notes             TEXT,
    -- Снимок медданных пациента на момент записи
    patient_health_info TEXT,
    -- Заключение врача после приёма
    doctor_conclusion TEXT,
    -- Флаги отправки напоминаний
    reminder_24h_sent BOOLEAN            NOT NULL DEFAULT FALSE,
    reminder_1h_sent  BOOLEAN            NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ        NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ        NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_appointments_schedule UNIQUE (schedule_id),
    CONSTRAINT chk_appointments_notes   CHECK (notes IS NULL OR char_length(notes) <= 2000)
);

CREATE INDEX idx_appointments_user_status ON appointments (user_id, status);
CREATE INDEX idx_appointments_doctor      ON appointments (doctor_id);
CREATE INDEX idx_appointments_status      ON appointments (status);
CREATE INDEX idx_appointments_created     ON appointments (created_at DESC);
-- Индекс для поиска записей, которым нужно отправить напоминания
CREATE INDEX idx_appointments_reminders   ON appointments (status, reminder_24h_sent, reminder_1h_sent)
    WHERE status = 'scheduled';

CREATE TRIGGER trg_appointments_updated_at
    BEFORE UPDATE ON appointments
    FOR EACH ROW EXECUTE FUNCTION trigger_set_updated_at();

CREATE OR REPLACE FUNCTION trg_appointment_manage_slot()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        UPDATE schedules SET is_available = FALSE
        WHERE schedule_id = NEW.schedule_id;
    ELSIF TG_OP = 'UPDATE' THEN
        IF NEW.status = 'cancelled' AND OLD.status <> 'cancelled' THEN
            UPDATE schedules SET is_available = TRUE
            WHERE schedule_id = NEW.schedule_id;
        END IF;
        IF OLD.status = 'cancelled' AND NEW.status = 'scheduled' THEN
            UPDATE schedules SET is_available = FALSE
            WHERE schedule_id = NEW.schedule_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_appointments_slot
    AFTER INSERT OR UPDATE ON appointments
    FOR EACH ROW EXECUTE FUNCTION trg_appointment_manage_slot();

-- ============================================================
-- 12. TABLE: symptoms_requests
-- ============================================================

CREATE TABLE symptoms_requests (
    request_id    UUID        PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id       UUID        NOT NULL REFERENCES users(user_id) ON UPDATE CASCADE,
    symptoms_text TEXT        NOT NULL,
    has_response  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_symptoms_text_len CHECK (
        char_length(symptoms_text) BETWEEN 20 AND 5000
    )
);

CREATE INDEX idx_symptoms_user_id    ON symptoms_requests (user_id);
CREATE INDEX idx_symptoms_created_at ON symptoms_requests (created_at DESC);

-- ============================================================
-- 13. TABLE: ai_responses
-- ============================================================

CREATE TABLE ai_responses (
    response_id    UUID          PRIMARY KEY DEFAULT uuid_generate_v4(),
    request_id     UUID          NOT NULL REFERENCES symptoms_requests(request_id) ON UPDATE CASCADE,
    diagnosis      TEXT          NOT NULL,
    recommendations TEXT         NOT NULL,
    urgency        urgency_level NOT NULL DEFAULT 'normal',
    model_version  VARCHAR(50)   NOT NULL,
    confidence     NUMERIC(5,4)  NOT NULL,
    processing_ms  INTEGER,
    raw_response   JSONB,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_ai_responses_request UNIQUE (request_id),
    CONSTRAINT chk_ai_confidence       CHECK (confidence BETWEEN 0.0 AND 1.0),
    CONSTRAINT chk_ai_processing_ms    CHECK (processing_ms IS NULL OR processing_ms > 0),
    CONSTRAINT chk_ai_model_version    CHECK (char_length(model_version) >= 1)
);

CREATE INDEX idx_ai_responses_request    ON ai_responses (request_id);
CREATE INDEX idx_ai_responses_urgency    ON ai_responses (urgency);
CREATE INDEX idx_ai_responses_confidence ON ai_responses (confidence DESC);

CREATE OR REPLACE FUNCTION trg_ai_response_set_flag()
RETURNS TRIGGER AS $$
BEGIN
    UPDATE symptoms_requests SET has_response = TRUE
    WHERE request_id = NEW.request_id;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ai_responses_flag
    AFTER INSERT ON ai_responses
    FOR EACH ROW EXECUTE FUNCTION trg_ai_response_set_flag();

-- ============================================================
-- 14. TABLE: notifications
-- ============================================================

CREATE TABLE notifications (
    notification_id UUID                 PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID                 NOT NULL REFERENCES users(user_id) ON UPDATE CASCADE,
    title           VARCHAR(255)         NOT NULL,
    body            TEXT                 NOT NULL,
    is_read         BOOLEAN              NOT NULL DEFAULT FALSE,
    channel         notification_channel NOT NULL,
    appointment_id  UUID                 REFERENCES appointments(appointment_id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ          NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_notification_title CHECK (char_length(title) >= 1),
    CONSTRAINT chk_notification_body  CHECK (char_length(body)  >= 1)
);

CREATE INDEX idx_notifications_user_unread ON notifications (user_id, is_read)    WHERE is_read = FALSE;
CREATE INDEX idx_notifications_user_all    ON notifications (user_id, created_at DESC);

-- ============================================================
-- 15. TABLE: user_documents
-- ============================================================

CREATE TABLE user_documents (
    document_id UUID         PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id     UUID         NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    file_name   VARCHAR(255) NOT NULL,
    file_type   VARCHAR(50)  NOT NULL,
    file_path   TEXT         NOT NULL,
    file_size   BIGINT       NOT NULL DEFAULT 0 CHECK (file_size >= 0),
    category    VARCHAR(50)  NOT NULL DEFAULT 'other',
    description VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_documents_user ON user_documents (user_id);

-- ============================================================
-- 16. TABLE: logs  (партиционировано по created_at — по годам)
-- ============================================================

CREATE TABLE logs (
    log_id     BIGINT       NOT NULL GENERATED ALWAYS AS IDENTITY,
    user_id    UUID         REFERENCES users(user_id) ON DELETE SET NULL,
    action     VARCHAR(255) NOT NULL,
    ip_address INET,
    user_agent TEXT,
    meta       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_logs_action CHECK (char_length(action) >= 1),
    PRIMARY KEY (log_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE TABLE logs_2025   PARTITION OF logs FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE logs_2026   PARTITION OF logs FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE logs_2027   PARTITION OF logs FOR VALUES FROM ('2027-01-01') TO ('2028-01-01');
CREATE TABLE logs_default PARTITION OF logs DEFAULT;

CREATE INDEX idx_logs_user_id  ON logs (user_id)     WHERE user_id IS NOT NULL;
CREATE INDEX idx_logs_action   ON logs (action);
CREATE INDEX idx_logs_meta_gin ON logs USING GIN (meta);
CREATE INDEX idx_logs_created  ON logs (created_at DESC);

-- ============================================================
-- 17. VIEWS для Exposed ORM
-- ============================================================

CREATE OR REPLACE VIEW v_available_slots AS
SELECT
    s.schedule_id,
    s.slot_date,
    s.slot_time,
    s.duration_minutes,
    d.doctor_id,
    d.full_name        AS doctor_name,
    d.photo_url        AS doctor_photo,
    d.rating           AS doctor_rating,
    d.experience_years AS doctor_experience,
    sp.name            AS specialization,
    c.clinic_id,
    c.clinic_name,
    c.address          AS clinic_address,
    c.phone            AS clinic_phone
FROM schedules s
JOIN doctors d          ON d.doctor_id          = s.doctor_id
JOIN specializations sp ON sp.specialization_id  = d.specialization_id
JOIN clinics c          ON c.clinic_id           = d.clinic_id
WHERE s.is_available = TRUE
  AND s.slot_date   >= CURRENT_DATE
  AND d.is_active   = TRUE
  AND c.is_active   = TRUE;

CREATE OR REPLACE VIEW v_user_appointments AS
SELECT
    a.appointment_id,
    a.user_id,
    a.status,
    a.notes,
    a.created_at        AS booked_at,
    s.slot_date,
    s.slot_time,
    s.duration_minutes,
    d.doctor_id,
    d.full_name         AS doctor_name,
    d.photo_url         AS doctor_photo,
    d.rating            AS doctor_rating,
    sp.name             AS specialization,
    c.clinic_name,
    c.address           AS clinic_address,
    c.phone             AS clinic_phone
FROM appointments a
JOIN schedules      s   ON s.schedule_id         = a.schedule_id
JOIN doctors        d   ON d.doctor_id           = a.doctor_id
JOIN specializations sp ON sp.specialization_id   = d.specialization_id
JOIN clinics        c   ON c.clinic_id           = d.clinic_id;

-- ============================================================
-- 18. SEED DATA
-- ============================================================

-- Роли
INSERT INTO roles (role_name) VALUES
    ('guest'),
    ('patient'),
    ('admin');

-- Специализации
-- IDs назначаются по порядку: 1=Терапевт, 2=Кардиолог, 3=Невролог,
-- 4=Ортопед, 5=Психолог, 6=ЛОР, 7=Педиатр, 8=Дерматолог
INSERT INTO specializations (name, description) VALUES
    ('Терапевт',   'Первичная медицинская помощь, диагностика и лечение'),
    ('Кардиолог',  'Заболевания сердечно-сосудистой системы'),
    ('Невролог',   'Заболевания нервной системы'),
    ('Ортопед',    'Опорно-двигательный аппарат'),
    ('Психолог',   'Психологическая помощь и консультации'),
    ('ЛОР',        'Ухо, горло, нос'),
    ('Педиатр',    'Лечение детей до 18 лет'),
    ('Дерматолог', 'Кожные заболевания');

-- Клиники
INSERT INTO clinics (clinic_name, address, phone, latitude, longitude) VALUES
    ('Клиника ЕВА — Центр', 'г. Москва, ул. Арбат, д. 10',      '+74951234567', 55.7494, 37.5986),
    ('Клиника ЕВА — Север', 'г. Москва, ул. Дмитровская, д. 5', '+74959876543', 55.8074, 37.5800);

-- Врачи — минимум один на каждую специализацию
INSERT INTO doctors (full_name, clinic_id, specialization_id, experience_years, bio) VALUES
    -- Кардиолог (spec 2)
    ('Холин Максим Владимирович',    1, 2, 12,
     'Кардиолог высшей категории. Специализируется на диагностике и лечении ИБС, гипертонии и аритмий.'),
    -- Психолог (spec 5)
    ('Жилина Мария Сергеевна',       1, 5, 8,
     'Клинический психолог, когнитивно-поведенческая терапия. Работает с тревожными расстройствами и депрессией.'),
    -- Ортопед (spec 4)
    ('Андреева Евгения Петровна',    2, 4, 15,
     'Ортопед-травматолог. Специализируется на заболеваниях суставов и позвоночника, спортивных травмах.'),
    -- Ортопед (spec 4) — второй
    ('Кокорин Иван Александрович',   2, 4, 6,
     'Ортопед. Консервативное лечение заболеваний опорно-двигательного аппарата.'),
    -- Психолог (spec 5) — второй
    ('Петрова Алиса Дмитриевна',     1, 5, 10,
     'Психолог, семейный консультант. Помощь в кризисных ситуациях и проблемах межличностных отношений.'),
    -- Терапевт (spec 1)
    ('Иванова Светлана Николаевна',  1, 1, 18,
     'Терапевт высшей категории. Диагностика и лечение острых и хронических заболеваний, профилактические осмотры.'),
    -- Невролог (spec 3)
    ('Соколов Дмитрий Андреевич',    2, 3, 11,
     'Невролог. Диагностика и лечение головных болей, остеохондроза, нарушений сна и вегетативных расстройств.'),
    -- ЛОР (spec 6)
    ('Морозова Елена Игоревна',      1, 6, 9,
     'Оториноларинголог. Лечение заболеваний уха, горла, носа у взрослых. Эндоскопическая диагностика.'),
    -- Педиатр (spec 7)
    ('Козлова Наталья Викторовна',   2, 7, 14,
     'Педиатр высшей категории. Ведение детей от рождения до 18 лет, вакцинация, профилактические осмотры.'),
    -- Дерматолог (spec 8)
    ('Новиков Артём Олегович',       1, 8, 7,
     'Дерматолог-косметолог. Лечение акне, экземы, псориаза, дерматитов. Дерматоскопия.');

-- Тестовый пользователь
-- Пароль в plain-text: TestEva2025!
-- Хэш: bcrypt, rounds=12
INSERT INTO users (
    full_name, email, phone, password_hash, role_id,
    consent_medical, consent_ai, consent_at
) VALUES (
    'Смольников Никита Сергеевич',
    'smolnikita@gmail.com',
    '+79001234567',
    '$2b$12$KIx3d9vC4hZMWJLo1mBCBOpTI1YqAAbVITB6LWBVJLuRnq8GzKzUy',
    2,
    TRUE, TRUE, NOW()
);

-- Запрос симптомов и ответ AI
WITH new_request AS (
    INSERT INTO symptoms_requests (user_id, symptoms_text)
    SELECT user_id,
           'Уже 3 дня страдаю от головной боли и простуды. Принимал ибупрофен, боль не проходит. Температура 36.8, насморк, усталость.'
    FROM users WHERE email = 'smolnikita@gmail.com'
    RETURNING request_id
)
INSERT INTO ai_responses (
    request_id, diagnosis, recommendations,
    urgency, model_version, confidence, processing_ms, raw_response
)
SELECT
    nr.request_id,
    'Вероятное направление: ОРВИ. Возможна головная боль напряжения.',
    'Рекомендуется обратиться к терапевту в течение 1–2 дней. Обильное питьё, постельный режим. ВАЖНО: данный анализ не является медицинским диагнозом.',
    'normal',
    'eva-ai-v1.0.0',
    0.7340,
    312,
    '{"model":"eva-ai-v1.0.0","tokens":124,"processing_ms":312}'::jsonb
FROM new_request nr;

-- ============================================================
-- END OF MIGRATION V1
-- ============================================================
