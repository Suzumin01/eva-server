package com.eva.service

import com.eva.data.repository.NotificationRepositoryImpl
import com.eva.data.repository.UserRepositoryImpl
import com.eva.data.tables.FcmTokensTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

class NotificationService(
    private val notificationRepository: NotificationRepositoryImpl,
    private val fcmService: FcmService,
    private val userRepository: UserRepositoryImpl
) {
    suspend fun notifyAppointmentCreated(
        userId: UUID,
        appointmentId: UUID,
        doctorName: String,
        date: String,
        time: String
    ) {
        val title = "Запись подтверждена ✓"
        val body  = "Вы записаны к врачу $doctorName на $date в $time"

        // Сохранить в БД
        notificationRepository.create(
            userId        = userId,
            title         = title,
            body          = body,
            channel       = "push",
            appointmentId = appointmentId
        )

        // Отправить push на все активные устройства пользователя
        val tokens = getUserTokens(userId)
        tokens.forEach { token ->
            fcmService.sendToToken(
                token = token,
                title = title,
                body  = body,
                data  = mapOf(
                    "type"           to "appointment_created",
                    "appointmentId"  to appointmentId.toString()
                )
            )
        }
    }

    suspend fun notifyAppointmentCancelled(
        userId: UUID,
        appointmentId: UUID,
        doctorName: String
    ) {
        val title = "Запись отменена"
        val body  = "Ваша запись к врачу $doctorName отменена"

        notificationRepository.create(
            userId        = userId,
            title         = title,
            body          = body,
            channel       = "push",
            appointmentId = appointmentId
        )

        getUserTokens(userId).forEach { token ->
            fcmService.sendToToken(token, title, body,
                mapOf("type" to "appointment_cancelled", "appointmentId" to appointmentId.toString()))
        }
    }

    private fun getUserTokens(userId: UUID): List<String> = transaction {
        FcmTokensTable
            .select { FcmTokensTable.userId eq userId and (FcmTokensTable.isActive eq true) }
            .map { it[FcmTokensTable.token] }
    }
}
