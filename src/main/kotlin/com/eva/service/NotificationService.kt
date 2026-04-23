package com.eva.service

import com.eva.data.repository.AppointmentRepositoryImpl
import com.eva.data.repository.FcmTokenRepositoryImpl
import com.eva.data.repository.NotificationRepositoryImpl
import java.time.format.DateTimeFormatter
import java.util.UUID

class NotificationService(
    private val notificationRepository: NotificationRepositoryImpl,
    private val fcmService: FcmService,
    private val fcmTokenRepository: FcmTokenRepositoryImpl,
    private val appointmentRepository: AppointmentRepositoryImpl
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

    // Вызывается каждые 5 минут из планировщика в Routing.kt
    suspend fun sendPendingReminders() {
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        appointmentRepository.findAndMarkPendingReminders24h().forEach { appt ->
            val timeStr = appt.slotTime.format(timeFmt)
            val title   = "Напоминание о приёме"
            val body    = "Завтра в $timeStr к ${appt.doctorName}"
            val notifId = notificationRepository.create(
                userId        = appt.userId,
                title         = title,
                body          = body,
                channel       = "reminders",
                appointmentId = appt.appointmentId
            )
            sendPush(appt.userId, title, body, mapOf(
                "type"          to "appointment_reminder_24h",
                "appointmentId" to appt.appointmentId.toString(),
                "notifId"       to notifId.toString()
            ))
        }

        appointmentRepository.findAndMarkPendingReminders1h().forEach { appt ->
            val timeStr = appt.slotTime.format(timeFmt)
            val title   = "Напоминание о приёме"
            val body    = "Через час в $timeStr к ${appt.doctorName}"
            val notifId = notificationRepository.create(
                userId        = appt.userId,
                title         = title,
                body          = body,
                channel       = "reminders",
                appointmentId = appt.appointmentId
            )
            sendPush(appt.userId, title, body, mapOf(
                "type"          to "appointment_reminder_1h",
                "appointmentId" to appt.appointmentId.toString(),
                "notifId"       to notifId.toString()
            ))
        }
    }

    private suspend fun sendPush(userId: UUID, title: String, body: String, data: Map<String, String>) {
        val tokens = fcmTokenRepository.getActiveTokens(userId)
        if (tokens.isNotEmpty()) {
            fcmService.sendToTokens(tokens, title, body, data)
        }
    }
}