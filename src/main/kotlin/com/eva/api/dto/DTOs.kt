package com.eva.api.dto

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val fullName: String,
    val email: String,
    val phone: String? = null,
    val password: String,
    val consentMedical: Boolean = false,
    val consentAi: Boolean = false
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val refreshToken: String,
    val userId: String,
    val fullName: String,
    val role: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class RefreshResponse(
    val token: String,
    val refreshToken: String
)

@Serializable
data class RegisterResponse(
    val userId: String,
    val message: String = "Регистрация прошла успешно"
)

@Serializable
data class UserProfileDto(
    val userId: String,
    val fullName: String,
    val email: String,
    val phone: String? = null,
    val role: String,
    val isActive: Boolean,
    val consentMedical: Boolean,
    val consentAi: Boolean,
    val avatarUrl: String? = null,
    val dateOfBirth: String? = null,
    val allergies: String? = null,
    val chronicDiseases: String? = null,
    val insurancePolicy: String? = null
)

@Serializable
data class UpdateProfileRequest(
    val fullName: String? = null,
    val phone: String? = null,
    val dateOfBirth: String? = null,
    val allergies: String? = null,
    val chronicDiseases: String? = null,
    val insurancePolicy: String? = null
)

@Serializable
data class DocumentResponse(
    val documentId:  String,
    val fileName:    String,
    val fileType:    String,
    val fileSize:    Long,
    val category:    String,
    val description: String? = null,
    val createdAt:   String,
    val downloadUrl: String
)

@Serializable
data class DoctorResponse(
    val doctorId: Int,
    val fullName: String,
    val clinicId: Int,
    val clinicName: String,
    val clinicAddress: String,
    val specializationId: Int,
    val specializationName: String,
    val bio: String?,
    val photoUrl: String?,
    val experienceYears: Int?,
    val rating: String?,
    val reviewsCount: Int
)

@Serializable
data class DoctorListResponse(
    val doctors: List<DoctorResponse>,
    val total: Int
)

@Serializable
data class ReviewResponse(
    val reviewId: String,
    val userId: String,
    val userFullName: String,
    val rating: Int,
    val comment: String?,
    val createdAt: String
)

@Serializable
data class UpdateReviewRequest(
    val rating: Int,
    val comment: String? = null
)

@Serializable
data class AddReviewRequest(
    val rating: Int,
    val comment: String? = null
)

@Serializable
data class SpecializationResponse(
    val specializationId: Int,
    val name: String,
    val description: String? = null
)

@Serializable
data class ClinicResponse(
    val clinicId: Int,
    val clinicName: String,
    val address: String,
    val phone: String?,
    val latitude: String?,
    val longitude: String?,
    val rating: String? = null,
    val doctorsCount: Int = 0
)

@Serializable
data class ScheduleResponse(
    val scheduleId: Long,
    val doctorId: Int,
    val doctorName: String,
    val slotDate: String,
    val slotTime: String,
    val durationMinutes: Int,
    val isAvailable: Boolean
)

@Serializable
data class CreateAppointmentRequest(
    val doctorId: Int,
    val scheduleId: Long,
    val notes: String? = null
)

@Serializable
data class AppointmentResponse(
    val appointmentId: String,
    val doctorId: Int,
    val doctorName: String,
    val specializationName: String,
    val clinicName: String,
    val clinicAddress: String,
    val slotDate: String,
    val slotTime: String,
    val durationMinutes: Int,
    val status: String,
    val notes: String?,
    val doctorConclusion: String? = null,
    val patientHealthInfo: String? = null,
    val createdAt: String
)

@Serializable
data class SetConclusionRequest(val conclusion: String)

@Serializable
data class AnalyzeSymptomsRequest(
    val symptomsText: String
)

@Serializable
data class AnalyzeSymptomsResponse(
    val requestId: String,
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val confidence: String,
    val modelVersion: String,
    val processingMs: Int?,
    val isStub: Boolean,
    val specializationName: String? = null,
    val disclaimer: String = "⚠️ Данный анализ является предварительным и не заменяет консультацию врача."
)

@Serializable
data class SymptomsHistoryResponse(
    val requestId: String,
    val symptomsText: String,
    val hasResponse: Boolean,
    val createdAt: String,
    val aiResponse: AiResponseDto?
)

@Serializable
data class AiResponseDto(
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val confidence: String,
    val modelVersion: String
)

@Serializable
data class NotificationResponse(
    val notificationId: String,
    val title: String,
    val body: String,
    val isRead: Boolean,
    val channel: String,
    val appointmentId: String?,
    val createdAt: String
)

@Serializable
data class RegisterFcmTokenRequest(
    val token: String,
    val deviceId: String? = null,
    val platform: String = "android"
)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class HealthResponse(
    val status: String = "ok",
    val version: String = "1.0.0",
    val service: String = "EVA Backend"
)

@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ForgotPasswordResponse(
    val message: String,
    // В production этого поля нет — токен приходит на email.
    // Для MVP/demo возвращаем токен напрямую.
    val resetToken: String? = null
)

@Serializable
data class ResetPasswordRequest(val token: String, val newPassword: String)