package com.eva.data.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.time
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object RolesTable : Table("roles") {
    val roleId   = short("role_id").autoIncrement()
    val roleName = varchar("role_name", 50).uniqueIndex()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(roleId)
}

object UsersTable : Table("users") {
    val userId         = uuid("user_id").clientDefault { java.util.UUID.randomUUID() }
    val fullName       = varchar("full_name", 255)
    val email          = varchar("email", 255).uniqueIndex()
    val phone          = varchar("phone", 20).nullable().uniqueIndex()
    val passwordHash   = varchar("password_hash", 255)
    val roleId         = short("role_id").references(RolesTable.roleId)
    val dateOfBirth    = date("date_of_birth").nullable()
    val avatarUrl      = varchar("avatar_url", 500).nullable()
    val consentMedical = bool("consent_medical").default(false)
    val consentAi      = bool("consent_ai").default(false)
    val consentAt      = timestampWithTimeZone("consent_at").nullable()
    val isActive       = bool("is_active").default(true)
    val lastLoginAt    = timestampWithTimeZone("last_login_at").nullable()
    val createdAt      = timestampWithTimeZone("created_at")
    val updatedAt      = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

object FcmTokensTable : Table("fcm_tokens") {
    val tokenId  = long("token_id").autoIncrement()
    val userId   = uuid("user_id").references(UsersTable.userId)
    val token    = text("token").uniqueIndex()
    val deviceId = varchar("device_id", 255).nullable()
    val platform = varchar("platform", 10).default("android")
    val isActive = bool("is_active").default(true)
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(tokenId)
}

object ClinicsTable : Table("clinics") {
    val clinicId   = integer("clinic_id").autoIncrement()
    val clinicName = varchar("clinic_name", 255)
    val address    = varchar("address", 500)
    val phone      = varchar("phone", 20).nullable()
    val website    = varchar("website", 255).nullable()
    val latitude   = decimal("latitude", 10, 7).nullable()
    val longitude  = decimal("longitude", 10, 7).nullable()
    val isActive   = bool("is_active").default(true)
    val createdAt  = timestampWithTimeZone("created_at")
    val updatedAt  = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(clinicId)
}

object SpecializationsTable : Table("specializations") {
    val specializationId = short("specialization_id").autoIncrement()
    val name             = varchar("name", 150).uniqueIndex()
    val description      = text("description").nullable()
    val iconUrl          = varchar("icon_url", 500).nullable()
    val createdAt        = timestampWithTimeZone("created_at")
    val updatedAt        = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(specializationId)
}

object DoctorsTable : Table("doctors") {
    val doctorId        = integer("doctor_id").autoIncrement()
    val fullName        = varchar("full_name", 255)
    val clinicId        = integer("clinic_id").references(ClinicsTable.clinicId)
    val specializationId = short("specialization_id").references(SpecializationsTable.specializationId)
    val bio             = text("bio").nullable()
    val photoUrl        = varchar("photo_url", 500).nullable()
    val experienceYears = short("experience_years").nullable()
    val rating          = decimal("rating", 3, 2).nullable()
    val reviewsCount    = integer("reviews_count").default(0)
    val isActive        = bool("is_active").default(true)
    val createdAt       = timestampWithTimeZone("created_at")
    val updatedAt       = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(doctorId)
}

object DoctorReviewsTable : Table("doctor_reviews") {
    val reviewId  = uuid("review_id").clientDefault { java.util.UUID.randomUUID() }
    val doctorId  = integer("doctor_id").references(DoctorsTable.doctorId)
    val userId    = uuid("user_id").references(UsersTable.userId)
    val rating    = short("rating")
    val comment   = text("comment").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(reviewId)
}

object SchedulesTable : Table("schedules") {
    val scheduleId      = long("schedule_id").autoIncrement()
    val doctorId        = integer("doctor_id").references(DoctorsTable.doctorId)
    val slotDate        = date("slot_date")
    val slotTime        = time("slot_time")
    val durationMinutes = short("duration_minutes").default(30)
    val isAvailable     = bool("is_available").default(true)
    val createdAt       = timestampWithTimeZone("created_at")
    val updatedAt       = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(scheduleId)
}

object AppointmentsTable : Table("appointments") {
    val appointmentId = uuid("appointment_id").clientDefault { java.util.UUID.randomUUID() }
    val userId        = uuid("user_id").references(UsersTable.userId)
    val doctorId      = integer("doctor_id").references(DoctorsTable.doctorId)
    val scheduleId    = long("schedule_id").references(SchedulesTable.scheduleId).uniqueIndex()
    val status        = varchar("status", 20).default("scheduled")
    val notes         = text("notes").nullable()
    val createdAt     = timestampWithTimeZone("created_at")
    val updatedAt     = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(appointmentId)
}

object SymptomsRequestsTable : Table("symptoms_requests") {
    val requestId    = uuid("request_id").clientDefault { java.util.UUID.randomUUID() }
    val userId       = uuid("user_id").references(UsersTable.userId)
    val symptomsText = text("symptoms_text")
    val hasResponse  = bool("has_response").default(false)
    val createdAt    = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(requestId)
}

object AiResponsesTable : Table("ai_responses") {
    val responseId      = uuid("response_id").clientDefault { java.util.UUID.randomUUID() }
    val requestId       = uuid("request_id").references(SymptomsRequestsTable.requestId).uniqueIndex()
    val diagnosis       = text("diagnosis")
    val recommendations = text("recommendations")
    val urgency         = varchar("urgency", 20).default("normal")
    val modelVersion    = varchar("model_version", 50)
    val confidence      = decimal("confidence", 5, 4)
    val processingMs    = integer("processing_ms").nullable()
    val rawResponse     = text("raw_response").nullable()   // JSONB хранится как text
    val createdAt       = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(responseId)
}

object NotificationsTable : Table("notifications") {
    val notificationId = uuid("notification_id").clientDefault { java.util.UUID.randomUUID() }
    val userId         = uuid("user_id").references(UsersTable.userId)
    val title          = varchar("title", 255)
    val body           = text("body")
    val isRead         = bool("is_read").default(false)
    val channel        = varchar("channel", 20)
    val appointmentId  = uuid("appointment_id").nullable()
    val createdAt      = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(notificationId)
}

object UserDocumentsTable : Table("user_documents") {
    val documentId  = uuid("document_id").clientDefault { java.util.UUID.randomUUID() }
    val userId      = uuid("user_id").references(UsersTable.userId)
    val fileName    = varchar("file_name", 255)
    val fileType    = varchar("file_type", 50)
    val filePath    = varchar("file_path", 500)
    val fileSize    = long("file_size").default(0)
    val category    = varchar("category", 50).default("other")
    val description = varchar("description", 500).nullable()
    val createdAt   = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(documentId)
}

object LogsTable : Table("logs") {
    val logId     = long("log_id").autoIncrement()
    val userId    = uuid("user_id").nullable()
    val action    = varchar("action", 255)
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = text("user_agent").nullable()
    val meta      = text("meta").default("{}")   // JSONB как text
    val createdAt = timestampWithTimeZone("created_at")
    // Примечание: таблица logs партиционирована в БД, PK составной (log_id, created_at)
    // Exposed работает с ней как с обычной таблицей через партиционированный PK
    override val primaryKey = PrimaryKey(logId)
}