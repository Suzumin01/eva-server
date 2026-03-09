package com.eva.domain.models

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

data class User(
    val userId: UUID,
    val fullName: String,
    val email: String,
    val phone: String?,
    val passwordHash: String,
    val roleId: Short,
    val roleName: String,
    val isActive: Boolean,
    val consentMedical: Boolean,
    val consentAi: Boolean,
    val lastLoginAt: OffsetDateTime?,
    val createdAt: OffsetDateTime
)

data class Doctor(
    val doctorId: Int,
    val fullName: String,
    val clinicId: Int,
    val clinicName: String,
    val clinicAddress: String,
    val specializationId: Short,
    val specializationName: String,
    val bio: String?,
    val photoUrl: String?,
    val experienceYears: Short?,
    val rating: BigDecimal?,
    val reviewsCount: Int,
    val isActive: Boolean
)

data class Clinic(
    val clinicId: Int,
    val clinicName: String,
    val address: String,
    val phone: String?,
    val website: String?,
    val latitude: BigDecimal?,
    val longitude: BigDecimal?,
    val rating: BigDecimal? = null,
    val doctorsCount: Int = 0
)

data class Schedule(
    val scheduleId: Long,
    val doctorId: Int,
    val doctorName: String,
    val slotDate: LocalDate,
    val slotTime: LocalTime,
    val durationMinutes: Short,
    val isAvailable: Boolean
)

data class Appointment(
    val appointmentId: UUID,
    val userId: UUID,
    val doctorId: Int,
    val doctorName: String,
    val specializationName: String,
    val clinicName: String,
    val clinicAddress: String,
    val scheduleId: Long,
    val slotDate: LocalDate,
    val slotTime: LocalTime,
    val status: String,
    val notes: String?,
    val doctorConclusion: String?,
    val createdAt: OffsetDateTime
)

data class SymptomsRequest(
    val requestId: UUID,
    val userId: UUID,
    val symptomsText: String,
    val hasResponse: Boolean,
    val createdAt: OffsetDateTime
)

data class AiResponse(
    val responseId: UUID,
    val requestId: UUID,
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val modelVersion: String,
    val confidence: BigDecimal,
    val processingMs: Int?,
    val createdAt: OffsetDateTime
)

data class Notification(
    val notificationId: UUID,
    val userId: UUID,
    val title: String,
    val body: String,
    val isRead: Boolean,
    val channel: String,
    val appointmentId: UUID?,
    val createdAt: OffsetDateTime
)

data class DoctorReview(
    val reviewId: UUID,
    val doctorId: Int,
    val userId: UUID,
    val userFullName: String,
    val rating: Short,
    val comment: String?,
    val createdAt: OffsetDateTime
)