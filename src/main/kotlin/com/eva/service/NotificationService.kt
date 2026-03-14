package com.eva.service

import com.eva.data.repository.FcmTokenRepositoryImpl
import com.eva.data.repository.NotificationRepositoryImpl
import java.util.UUID

class NotificationService(
    private val notificationRepository: NotificationRepositoryImpl,
    private val fcmService: FcmService,
    private val fcmTokenRepository: FcmTokenRepositoryImpl
) {

    suspend fun notifyAppointmentCreated(
        userId: UUID,
        appointmentId: UUID,
        doctorName: String,
        date: String,
        time: String
    ) {
        val title   = "Запись подтверждена"
        val body    = "К $doctorName на $date в $time"

        // Сохраняем в БД — получаем notifId для диплинка
        val notifId = notificationRepository.create(
            userId        = userId,
            title         = title,
            body          = body,
            channel       = "appointments",
            appointmentId = appointmentId
        )

        sendPush(userId, title, body, mapOf(
            "type"          to "appointment_created",
            "appointmentId" to appointmentId.toString(),
            "notifId"       to notifId.toString()
        ))
    }

    suspend fun notifyAppointmentCancelled(
        userId: UUID,
        appointmentId: UUID,
        doctorName: String,
        date: String
    ) {
        val title   = "Запись отменена"
        val body    = "Приём к $doctorName $date отменён"

        val notifId = notificationRepository.create(
            userId        = userId,
            title         = title,
            body          = body,
            channel       = "appointments",
            appointmentId = appointmentId
        )

        sendPush(userId, title, body, mapOf(
            "type"          to "appointment_cancelled",
            "appointmentId" to appointmentId.toString(),
            "notifId"       to notifId.toString()
        ))
    }

    suspend fun notifyAppointmentReminder(
        userId: UUID,
        appointmentId: UUID,
        doctorName: String,
        time: String
    ) {
        val title   = "Напоминание о приёме"
        val body    = "Сегодня в $time к $doctorName"

        val notifId = notificationRepository.create(
            userId        = userId,
            title         = title,
            body          = body,
            channel       = "reminders",
            appointmentId = appointmentId
        )

        sendPush(userId, title, body, mapOf(
            "type"          to "appointment_reminder",
            "appointmentId" to appointmentId.toString(),
            "notifId"       to notifId.toString()
        ))
    }

    private suspend fun sendPush(userId: UUID, title: String, body: String, data: Map<String, String>) {
        val tokens = fcmTokenRepository.getActiveTokens(userId)
        if (tokens.isNotEmpty()) {
            fcmService.sendToTokens(tokens, title, body, data)
        }
    }
}