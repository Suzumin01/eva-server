-- Exposed sends plain VARCHAR; PostgreSQL 15 does not auto-cast VARCHAR → enum.
-- Converting all three custom enum columns to TEXT removes the type mismatch
-- while keeping existing values intact. Application-level validation is sufficient.
--
-- The partial index created in V2 (WHERE status = 'scheduled') stores the
-- predicate as appointment_status in the catalog.  PostgreSQL can't revalidate
-- it against TEXT (no 'text = appointment_status' operator), so it must be
-- dropped before the ALTER and recreated with a plain text predicate after.

DROP VIEW  IF EXISTS v_user_appointments;
DROP INDEX IF EXISTS idx_appointments_reminders;

-- appointments.status
ALTER TABLE appointments ALTER COLUMN status DROP DEFAULT;
ALTER TABLE appointments ALTER COLUMN status TYPE TEXT USING status::TEXT;
ALTER TABLE appointments ALTER COLUMN status SET DEFAULT 'scheduled';

-- ai_responses.urgency
ALTER TABLE ai_responses ALTER COLUMN urgency DROP DEFAULT;
ALTER TABLE ai_responses ALTER COLUMN urgency TYPE TEXT USING urgency::TEXT;
ALTER TABLE ai_responses ALTER COLUMN urgency SET DEFAULT 'normal';

-- notifications.channel  (no DEFAULT)
ALTER TABLE notifications ALTER COLUMN channel TYPE TEXT USING channel::TEXT;

-- Recreate partial index with text predicate
CREATE INDEX idx_appointments_reminders
    ON appointments (status, reminder_24h_sent, reminder_1h_sent)
    WHERE status = 'scheduled';

-- Recreate view
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
