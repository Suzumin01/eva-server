package com.eva.data.repository

import com.eva.data.tables.*
import com.eva.domain.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.jetbrains.exposed.sql.JoinType

class ScheduleRepositoryImpl {

    fun findByDoctor(doctorId: Int, date: LocalDate? = null): List<Schedule> = transaction {
        val query = (SchedulesTable innerJoin DoctorsTable)
            .select {
                SchedulesTable.doctorId eq doctorId and
                (SchedulesTable.isAvailable eq true) and
                (SchedulesTable.slotDate greaterEq LocalDate.now())
            }
        date?.let { query.andWhere { SchedulesTable.slotDate eq it } }
        query.orderBy(SchedulesTable.slotDate to SortOrder.ASC, SchedulesTable.slotTime to SortOrder.ASC)
            .map { it.toSchedule() }
    }

    fun findById(scheduleId: Long): Schedule? = transaction {
        (SchedulesTable innerJoin DoctorsTable)
            .select { SchedulesTable.scheduleId eq scheduleId }
            .singleOrNull()?.toSchedule()
    }

    fun isAvailable(scheduleId: Long): Boolean = transaction {
        SchedulesTable.select { SchedulesTable.scheduleId eq scheduleId }
            .singleOrNull()?.get(SchedulesTable.isAvailable) ?: false
    }

    private fun ResultRow.toSchedule() = Schedule(
        scheduleId      = this[SchedulesTable.scheduleId],
        doctorId        = this[SchedulesTable.doctorId],
        doctorName      = this[DoctorsTable.fullName],
        slotDate        = this[SchedulesTable.slotDate],
        slotTime        = this[SchedulesTable.slotTime],
        durationMinutes = this[SchedulesTable.durationMinutes],
        isAvailable     = this[SchedulesTable.isAvailable]
    )
}

class AppointmentRepositoryImpl {

    fun create(userId: UUID, doctorId: Int, scheduleId: Long, notes: String?): UUID = transaction {
        val available = SchedulesTable
            .select { SchedulesTable.scheduleId eq scheduleId }
            .forUpdate()
            .singleOrNull()?.get(SchedulesTable.isAvailable)
            ?: throw IllegalArgumentException("Слот расписания не найден")

        if (!available) throw IllegalArgumentException("Слот уже занят")

        val id = UUID.randomUUID()
        AppointmentsTable.insert {
            it[appointmentId]                = id
            it[AppointmentsTable.userId]     = userId
            it[AppointmentsTable.doctorId]   = doctorId
            it[AppointmentsTable.scheduleId] = scheduleId
            it[AppointmentsTable.notes]      = notes
            it[AppointmentsTable.status]     = "scheduled"
            it[createdAt]                    = OffsetDateTime.now()
            it[updatedAt]                    = OffsetDateTime.now()
        }

        SchedulesTable.update({ SchedulesTable.scheduleId eq scheduleId }) {
            it[isAvailable] = false
            it[updatedAt]   = OffsetDateTime.now()
        }
        id
    }

    fun findByUser(userId: UUID, status: String? = null): List<Appointment> = transaction {
        val query = AppointmentsTable
            .join(SchedulesTable, JoinType.INNER,
                onColumn = AppointmentsTable.scheduleId,
                otherColumn = SchedulesTable.scheduleId)
            .join(DoctorsTable, JoinType.INNER,
                onColumn = AppointmentsTable.doctorId,
                otherColumn = DoctorsTable.doctorId)
            .join(ClinicsTable, JoinType.INNER,
                onColumn = DoctorsTable.clinicId,
                otherColumn = ClinicsTable.clinicId)
            .join(SpecializationsTable, JoinType.INNER,
                onColumn = DoctorsTable.specializationId,
                otherColumn = SpecializationsTable.specializationId)
            .select { AppointmentsTable.userId eq userId }

        status?.let { query.andWhere { AppointmentsTable.status eq it } }

        query.orderBy(SchedulesTable.slotDate to SortOrder.DESC)
            .map { it.toAppointment() }
    }

    fun findById(appointmentId: UUID): Appointment? = transaction {
        AppointmentsTable
            .join(SchedulesTable, JoinType.INNER,
                onColumn = AppointmentsTable.scheduleId,
                otherColumn = SchedulesTable.scheduleId)
            .join(DoctorsTable, JoinType.INNER,
                onColumn = AppointmentsTable.doctorId,
                otherColumn = DoctorsTable.doctorId)
            .join(ClinicsTable, JoinType.INNER,
                onColumn = DoctorsTable.clinicId,
                otherColumn = ClinicsTable.clinicId)
            .join(SpecializationsTable, JoinType.INNER,
                onColumn = DoctorsTable.specializationId,
                otherColumn = SpecializationsTable.specializationId)
            .select { AppointmentsTable.appointmentId eq appointmentId }
            .singleOrNull()?.toAppointment()
    }

    fun cancel(appointmentId: UUID, userId: UUID): Boolean = transaction {
        val rows = AppointmentsTable.update({
            AppointmentsTable.appointmentId eq appointmentId and
            (AppointmentsTable.userId eq userId) and
            (AppointmentsTable.status eq "scheduled")
        }) {
            it[status]    = "cancelled"
            it[updatedAt] = OffsetDateTime.now()
        }

        if (rows > 0) {
            // Освобождаем слот
            val scheduleId = AppointmentsTable
                .select { AppointmentsTable.appointmentId eq appointmentId }
                .single()[AppointmentsTable.scheduleId]

            SchedulesTable.update({ SchedulesTable.scheduleId eq scheduleId }) {
                it[isAvailable] = true
                it[updatedAt]   = OffsetDateTime.now()
            }
        }
        rows > 0
    }

    fun complete(appointmentId: UUID): Boolean = transaction {
        AppointmentsTable.update({
            AppointmentsTable.appointmentId eq appointmentId and
            (AppointmentsTable.status eq "scheduled")
        }) {
            it[status]    = "completed"
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    private fun ResultRow.toAppointment() = Appointment(
        appointmentId      = this[AppointmentsTable.appointmentId],
        userId             = this[AppointmentsTable.userId],
        doctorId           = this[DoctorsTable.doctorId],
        doctorName         = this[DoctorsTable.fullName],
        specializationName = this[SpecializationsTable.name],
        clinicName         = this[ClinicsTable.clinicName],
        clinicAddress      = this[ClinicsTable.address],
        scheduleId         = this[SchedulesTable.scheduleId],
        slotDate           = this[SchedulesTable.slotDate],
        slotTime           = this[SchedulesTable.slotTime],
        status             = this[AppointmentsTable.status],
        notes              = this[AppointmentsTable.notes],
        createdAt          = this[AppointmentsTable.createdAt]
    )
}

class SymptomsRepositoryImpl {

    fun create(userId: UUID, symptomsText: String): UUID = transaction {
        val id = UUID.randomUUID()
        SymptomsRequestsTable.insert {
            it[requestId]    = id
            it[SymptomsRequestsTable.userId]       = userId
            it[SymptomsRequestsTable.symptomsText] = symptomsText
            it[createdAt]    = OffsetDateTime.now()
        }
        id
    }

    fun findByUser(userId: UUID): List<SymptomsRequest> = transaction {
        SymptomsRequestsTable
            .select { SymptomsRequestsTable.userId eq userId }
            .orderBy(SymptomsRequestsTable.createdAt to SortOrder.DESC)
            .map { it.toRequest() }
    }

    fun findById(requestId: UUID): SymptomsRequest? = transaction {
        SymptomsRequestsTable.select { SymptomsRequestsTable.requestId eq requestId }
            .singleOrNull()?.toRequest()
    }

    fun saveAiResponse(
        requestId: UUID,
        diagnosis: String,
        recommendations: String,
        urgency: String,
        modelVersion: String,
        confidence: BigDecimal,
        processingMs: Int?,
        rawResponse: String?
    ): UUID = transaction {
        val id = UUID.randomUUID()
        AiResponsesTable.insert {
            it[AiResponsesTable.responseId]      = id
            it[AiResponsesTable.requestId]       = requestId
            it[AiResponsesTable.diagnosis]       = diagnosis
            it[AiResponsesTable.recommendations] = recommendations
            it[AiResponsesTable.urgency]         = urgency
            it[AiResponsesTable.modelVersion]    = modelVersion
            it[AiResponsesTable.confidence]      = confidence
            it[AiResponsesTable.processingMs]    = processingMs
            it[AiResponsesTable.rawResponse]     = rawResponse
            it[AiResponsesTable.createdAt]       = OffsetDateTime.now()
        }
        SymptomsRequestsTable.update({ SymptomsRequestsTable.requestId eq requestId }) {
            it[hasResponse] = true
        }
        id
    }

    fun getAiResponse(requestId: UUID): AiResponse? = transaction {
        AiResponsesTable.select { AiResponsesTable.requestId eq requestId }
            .singleOrNull()?.let {
                AiResponse(
                    responseId      = it[AiResponsesTable.responseId],
                    requestId       = it[AiResponsesTable.requestId],
                    diagnosis       = it[AiResponsesTable.diagnosis],
                    recommendations = it[AiResponsesTable.recommendations],
                    urgency         = it[AiResponsesTable.urgency],
                    modelVersion    = it[AiResponsesTable.modelVersion],
                    confidence      = it[AiResponsesTable.confidence],
                    processingMs    = it[AiResponsesTable.processingMs],
                    createdAt       = it[AiResponsesTable.createdAt]
                )
            }
    }

    private fun ResultRow.toRequest() = SymptomsRequest(
        requestId    = this[SymptomsRequestsTable.requestId],
        userId       = this[SymptomsRequestsTable.userId],
        symptomsText = this[SymptomsRequestsTable.symptomsText],
        hasResponse  = this[SymptomsRequestsTable.hasResponse],
        createdAt    = this[SymptomsRequestsTable.createdAt]
    )
}

class NotificationRepositoryImpl {

    fun create(
        userId: UUID,
        title: String,
        body: String,
        channel: String,
        appointmentId: UUID? = null
    ): UUID = transaction {
        val id = UUID.randomUUID()
        NotificationsTable.insert {
            it[notificationId]          = id
            it[NotificationsTable.userId]        = userId
            it[NotificationsTable.title]         = title
            it[NotificationsTable.body]          = body
            it[NotificationsTable.channel]       = channel
            it[NotificationsTable.appointmentId] = appointmentId
            it[createdAt]               = OffsetDateTime.now()
        }
        id
    }

    fun findByUser(userId: UUID, onlyUnread: Boolean = false): List<Notification> = transaction {
        val query = NotificationsTable.select { NotificationsTable.userId eq userId }
        if (onlyUnread) query.andWhere { NotificationsTable.isRead eq false }
        query.orderBy(NotificationsTable.createdAt to SortOrder.DESC)
            .limit(50)
            .map { it.toNotification() }
    }

    fun markRead(notificationId: UUID, userId: UUID): Boolean = transaction {
        NotificationsTable.update({
            NotificationsTable.notificationId eq notificationId and
            (NotificationsTable.userId eq userId)
        }) { it[isRead] = true } > 0
    }

    fun markAllRead(userId: UUID): Int = transaction {
        NotificationsTable.update({ NotificationsTable.userId eq userId }) {
            it[isRead] = true
        }
    }

    private fun ResultRow.toNotification() = Notification(
        notificationId = this[NotificationsTable.notificationId],
        userId         = this[NotificationsTable.userId],
        title          = this[NotificationsTable.title],
        body           = this[NotificationsTable.body],
        isRead         = this[NotificationsTable.isRead],
        channel        = this[NotificationsTable.channel],
        appointmentId  = this[NotificationsTable.appointmentId],
        createdAt      = this[NotificationsTable.createdAt]
    )
}

class LogRepositoryImpl {

    fun log(
        userId: UUID?,
        action: String,
        ipAddress: String? = null,
        userAgent: String? = null,
        meta: String = "{}"
    ) = transaction {
        LogsTable.insert {
            it[LogsTable.userId]    = userId
            it[LogsTable.action]   = action
            it[LogsTable.ipAddress] = ipAddress
            it[LogsTable.userAgent] = userAgent
            it[LogsTable.meta]     = meta
            it[createdAt]          = OffsetDateTime.now()
        }
    }
}
