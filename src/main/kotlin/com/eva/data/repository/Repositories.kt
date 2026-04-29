package com.eva.data.repository

import com.eva.data.tables.*
import com.eva.domain.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.StatementType
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ReminderTarget(
    val appointmentId: UUID,
    val userId:        UUID,
    val doctorName:    String,
    val slotDate:      java.time.LocalDate,
    val slotTime:      java.time.LocalTime
)

class ScheduleRepositoryImpl(private val timezone: String = "Europe/Moscow") {

    fun findByDoctor(
        doctorId: Int,
        date: LocalDate? = null,
        dateTo: LocalDate? = null
    ): List<Schedule> = transaction {
        val zoneId  = java.time.ZoneId.of(timezone)
        val today   = LocalDate.now(zoneId)
        val nowTime = java.time.LocalTime.now(zoneId)

        val query = (SchedulesTable innerJoin DoctorsTable)
            .select {
                SchedulesTable.doctorId eq doctorId and
                        (SchedulesTable.isAvailable eq true) and
                        (SchedulesTable.slotDate greaterEq today)
            }
        // Если передан только date — точный день; если передан dateTo — date становится нижней границей диапазона
        if (dateTo != null) {
            date?.let  { query.andWhere { SchedulesTable.slotDate greaterEq it } }
            query.andWhere { SchedulesTable.slotDate lessEq dateTo }
        } else {
            date?.let  { query.andWhere { SchedulesTable.slotDate eq it } }
        }

        query.orderBy(SchedulesTable.slotDate to SortOrder.ASC, SchedulesTable.slotTime to SortOrder.ASC)
            .map { it.toSchedule() }
            .filter { slot ->
                slot.slotDate > today || (slot.slotDate == today && slot.slotTime > nowTime)
            }
    }

    fun findById(scheduleId: Long): Schedule? = transaction {
        (SchedulesTable innerJoin DoctorsTable)
            .select { SchedulesTable.scheduleId eq scheduleId }
            .singleOrNull()?.toSchedule()
    }

    fun create(doctorId: Int, slotDate: java.time.LocalDate, slotTime: java.time.LocalTime, durationMinutes: Short = 30): Long = transaction {
        SchedulesTable.insert {
            it[SchedulesTable.doctorId]        = doctorId
            it[SchedulesTable.slotDate]        = slotDate
            it[SchedulesTable.slotTime]        = slotTime
            it[SchedulesTable.durationMinutes] = durationMinutes
            it[SchedulesTable.isAvailable]     = true
            it[SchedulesTable.createdAt]       = OffsetDateTime.now()
            it[SchedulesTable.updatedAt]       = OffsetDateTime.now()
        }[SchedulesTable.scheduleId]
    }

    fun delete(scheduleId: Long): Boolean = transaction {
        SchedulesTable.deleteWhere {
            (SchedulesTable.scheduleId eq scheduleId) and (SchedulesTable.isAvailable eq true)
        } > 0
    }

    fun findAllByDoctor(
        doctorId: Int,
        dateFrom: java.time.LocalDate? = null,
        dateTo: java.time.LocalDate? = null
    ): List<Schedule> = transaction {
        val query = (SchedulesTable innerJoin DoctorsTable)
            .select { SchedulesTable.doctorId eq doctorId }
        dateFrom?.let { query.andWhere { SchedulesTable.slotDate greaterEq it } }
        dateTo?.let   { query.andWhere { SchedulesTable.slotDate lessEq it } }
        query.orderBy(SchedulesTable.slotDate to SortOrder.ASC, SchedulesTable.slotTime to SortOrder.ASC)
            .map { it.toSchedule() }
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

class AppointmentRepositoryImpl(private val timezone: String = "Europe/Moscow") {

    fun create(userId: UUID, scheduleId: Long, notes: String?): UUID = transaction {
        val scheduleRow = SchedulesTable
            .select { SchedulesTable.scheduleId eq scheduleId }
            .forUpdate()
            .singleOrNull() ?: throw IllegalArgumentException("Слот расписания не найден")

        val available = scheduleRow[SchedulesTable.isAvailable]
        if (!available) throw IllegalArgumentException("Слот уже занят")

        val doctorId = scheduleRow[SchedulesTable.doctorId]

        val userRow = UsersTable
            .slice(UsersTable.allergies, UsersTable.chronicDiseases)
            .select { UsersTable.userId eq userId }
            .singleOrNull()

        val patientHealthInfo = buildString {
            val allergies = userRow?.get(UsersTable.allergies)
            val chronic   = userRow?.get(UsersTable.chronicDiseases)
            if (!allergies.isNullOrBlank()) append("Аллергии: $allergies")
            if (!allergies.isNullOrBlank() && !chronic.isNullOrBlank()) append("\n")
            if (!chronic.isNullOrBlank()) append("Хронические заболевания: $chronic")
        }.ifBlank { null }

        val id = UUID.randomUUID()
        AppointmentsTable.insert {
            it[appointmentId] = id
            it[AppointmentsTable.userId]            = userId
            it[AppointmentsTable.doctorId]          = doctorId
            it[AppointmentsTable.scheduleId]        = scheduleId
            it[AppointmentsTable.notes]             = notes
            it[AppointmentsTable.doctorConclusion]  = null
            it[AppointmentsTable.patientHealthInfo] = patientHealthInfo
            it[AppointmentsTable.status]            = "scheduled"
            it[createdAt] = OffsetDateTime.now()
            it[updatedAt] = OffsetDateTime.now()
        }

        SchedulesTable.update({ SchedulesTable.scheduleId eq scheduleId }) {
            it[isAvailable] = false
            it[updatedAt]   = OffsetDateTime.now()
        }
        id
    }

    fun findByUser(userId: UUID, status: String? = null): List<Appointment> = transaction {
        val query = AppointmentsTable
            .innerJoin(DoctorsTable, { AppointmentsTable.doctorId }, { DoctorsTable.doctorId })
            .innerJoin(ClinicsTable, { DoctorsTable.clinicId }, { ClinicsTable.clinicId })
            .innerJoin(SpecializationsTable, { DoctorsTable.specializationId }, { SpecializationsTable.specializationId })
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .select { AppointmentsTable.userId eq userId }

        status?.let { query.andWhere { AppointmentsTable.status eq it } }

        query.orderBy(SchedulesTable.slotDate to SortOrder.DESC)
            .map { it.toAppointment() }
    }

    fun findById(appointmentId: UUID): Appointment? = transaction {
        AppointmentsTable
            .innerJoin(DoctorsTable, { AppointmentsTable.doctorId }, { DoctorsTable.doctorId })
            .innerJoin(ClinicsTable, { DoctorsTable.clinicId }, { ClinicsTable.clinicId })
            .innerJoin(SpecializationsTable, { DoctorsTable.specializationId }, { SpecializationsTable.specializationId })
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .select { AppointmentsTable.appointmentId eq appointmentId }
            .singleOrNull()?.toAppointment()
    }

    fun cancel(appointmentId: UUID, userId: UUID): Boolean = transaction {
        val scheduleId = AppointmentsTable
            .slice(AppointmentsTable.scheduleId)
            .select {
                AppointmentsTable.appointmentId eq appointmentId and
                        (AppointmentsTable.userId eq userId) and
                        (AppointmentsTable.status eq "scheduled")
            }
            .singleOrNull()
            ?.get(AppointmentsTable.scheduleId)
            ?: return@transaction false

        AppointmentsTable.update({
            AppointmentsTable.appointmentId eq appointmentId and
                    (AppointmentsTable.userId eq userId) and
                    (AppointmentsTable.status eq "scheduled")
        }) {
            it[status]    = "cancelled"
            it[updatedAt] = OffsetDateTime.now()
        }

        SchedulesTable.update({ SchedulesTable.scheduleId eq scheduleId }) {
            it[isAvailable] = true
            it[updatedAt]   = OffsetDateTime.now()
        }

        true
    }

    fun setConclusion(appointmentId: UUID, conclusion: String): Boolean = transaction {
        AppointmentsTable.update({ AppointmentsTable.appointmentId eq appointmentId }) {
            it[doctorConclusion] = conclusion
            it[updatedAt]        = OffsetDateTime.now()
        } > 0
    }

    fun findAndMarkPendingReminders24h(): List<ReminderTarget> =
        findAndMarkPending("reminder_24h_sent", fromMinutes = 23 * 60L, toMinutes = 25 * 60L)

    fun findAndMarkPendingReminders1h(): List<ReminderTarget> =
        findAndMarkPending("reminder_1h_sent", fromMinutes = 55L, toMinutes = 65L)

    private val dtFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun findAndMarkPending(
        flagColumn: String,
        fromMinutes: Long,
        toMinutes: Long
    ): List<ReminderTarget> = transaction {
        val now  = LocalDateTime.now(ZoneId.of(timezone))
        val from = now.plusMinutes(fromMinutes).format(dtFmt)
        val to   = now.plusMinutes(toMinutes).format(dtFmt)

        exec(
            stmt = """
            WITH due AS (
                SELECT a.appointment_id, a.user_id, d.full_name AS doctor_name,
                       s.slot_date, s.slot_time
                FROM appointments a
                JOIN schedules s ON s.schedule_id = a.schedule_id
                JOIN doctors   d ON d.doctor_id   = a.doctor_id
                WHERE a.status = 'scheduled'
                  AND a.$flagColumn = false
                  AND (s.slot_date + s.slot_time) BETWEEN '$from' AND '$to'
            ),
            updated AS (
                UPDATE appointments
                SET $flagColumn = true, updated_at = NOW()
                WHERE appointment_id IN (SELECT appointment_id FROM due)
                RETURNING appointment_id
            )
            SELECT due.* FROM due
            JOIN updated ON updated.appointment_id = due.appointment_id
            """,
            explicitStatementType = StatementType.SELECT
        ) { rs ->
            val list = mutableListOf<ReminderTarget>()
            while (rs.next()) {
                list.add(ReminderTarget(
                    appointmentId = UUID.fromString(rs.getString("appointment_id")),
                    userId        = UUID.fromString(rs.getString("user_id")),
                    doctorName    = rs.getString("doctor_name"),
                    slotDate      = rs.getObject("slot_date", java.time.LocalDate::class.java),
                    slotTime      = rs.getObject("slot_time", java.time.LocalTime::class.java)
                ))
            }
            list
        } ?: emptyList()
    }

    fun findAll(
        status: String? = null,
        doctorId: Int? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null,
        limit: Int = 20,
        offset: Long = 0
    ): List<Appointment> = transaction {
        val query = AppointmentsTable
            .innerJoin(DoctorsTable, { AppointmentsTable.doctorId }, { DoctorsTable.doctorId })
            .innerJoin(ClinicsTable, { DoctorsTable.clinicId }, { ClinicsTable.clinicId })
            .innerJoin(SpecializationsTable, { DoctorsTable.specializationId }, { SpecializationsTable.specializationId })
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .innerJoin(UsersTable, { AppointmentsTable.userId }, { UsersTable.userId })
            .selectAll()
        status?.let   { query.andWhere { AppointmentsTable.status eq it } }
        doctorId?.let { query.andWhere { AppointmentsTable.doctorId eq it } }
        dateFrom?.let { query.andWhere { SchedulesTable.slotDate greaterEq it } }
        dateTo?.let   { query.andWhere { SchedulesTable.slotDate lessEq it } }
        query.orderBy(AppointmentsTable.createdAt to SortOrder.DESC)
            .limit(limit, offset)
            .map { it.toAppointmentWithPatient() }
    }

    fun countAll(
        status: String? = null,
        doctorId: Int? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null
    ): Long = transaction {
        val query = AppointmentsTable
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .slice(AppointmentsTable.appointmentId.count())
            .selectAll()
        status?.let   { query.andWhere { AppointmentsTable.status eq it } }
        doctorId?.let { query.andWhere { AppointmentsTable.doctorId eq it } }
        dateFrom?.let { query.andWhere { SchedulesTable.slotDate greaterEq it } }
        dateTo?.let   { query.andWhere { SchedulesTable.slotDate lessEq it } }
        query.single()[AppointmentsTable.appointmentId.count()]
    }

    fun findByDoctor(
        doctorId: Int,
        status: String? = null,
        dateFrom: LocalDate? = null,
        dateTo: LocalDate? = null
    ): List<Appointment> = transaction {
        val query = AppointmentsTable
            .innerJoin(DoctorsTable, { AppointmentsTable.doctorId }, { DoctorsTable.doctorId })
            .innerJoin(ClinicsTable, { DoctorsTable.clinicId }, { ClinicsTable.clinicId })
            .innerJoin(SpecializationsTable, { DoctorsTable.specializationId }, { SpecializationsTable.specializationId })
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .innerJoin(UsersTable, { AppointmentsTable.userId }, { UsersTable.userId })
            .select { AppointmentsTable.doctorId eq doctorId }
        status?.let   { query.andWhere { AppointmentsTable.status eq it } }
        dateFrom?.let { query.andWhere { SchedulesTable.slotDate greaterEq it } }
        dateTo?.let   { query.andWhere { SchedulesTable.slotDate lessEq it } }
        query.orderBy(SchedulesTable.slotDate to SortOrder.DESC, SchedulesTable.slotTime to SortOrder.DESC)
            .map { it.toAppointmentWithPatient() }
    }

    fun updateStatus(appointmentId: UUID, doctorId: Int, newStatus: String): Boolean = transaction {
        AppointmentsTable.update({
            (AppointmentsTable.appointmentId eq appointmentId) and
            (AppointmentsTable.doctorId eq doctorId)
        }) {
            it[status]    = newStatus
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun setNotesAndConclusion(appointmentId: UUID, doctorId: Int, notes: String?, conclusion: String?): Boolean = transaction {
        AppointmentsTable.update({
            (AppointmentsTable.appointmentId eq appointmentId) and
            (AppointmentsTable.doctorId eq doctorId)
        }) { row ->
            notes?.let { row[AppointmentsTable.notes] = it }
            conclusion?.let { row[AppointmentsTable.doctorConclusion] = it }
            row[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    private fun ResultRow.toAppointmentWithPatient() = toAppointment().copy(
        patientName = this[UsersTable.fullName]
    )

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
        durationMinutes    = this[SchedulesTable.durationMinutes],
        status             = this[AppointmentsTable.status],
        notes              = this[AppointmentsTable.notes],
        doctorConclusion   = this[AppointmentsTable.doctorConclusion],
        patientHealthInfo  = this[AppointmentsTable.patientHealthInfo],
        createdAt          = this[AppointmentsTable.createdAt]
    )
}

class AdminStatsRepository {

    fun getAppointmentsByDay(days: Int, future: Boolean): List<Pair<String, Int>> = transaction {
        val today = LocalDate.now()
        val (dateFrom, dateTo) = if (future)
            today to today.plusDays(days.toLong() - 1)
        else
            today.minusDays(days.toLong() - 1) to today
        AppointmentsTable
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .slice(SchedulesTable.slotDate, AppointmentsTable.appointmentId.count())
            .select { SchedulesTable.slotDate greaterEq dateFrom and (SchedulesTable.slotDate lessEq dateTo) }
            .groupBy(SchedulesTable.slotDate)
            .orderBy(SchedulesTable.slotDate to SortOrder.ASC)
            .map { it[SchedulesTable.slotDate].toString() to it[AppointmentsTable.appointmentId.count()].toInt() }
    }

    fun getAppointmentStatuses(): List<Pair<String, Int>> = transaction {
        AppointmentsTable
            .slice(AppointmentsTable.status, AppointmentsTable.appointmentId.count())
            .selectAll()
            .groupBy(AppointmentsTable.status)
            .map { it[AppointmentsTable.status] to it[AppointmentsTable.appointmentId.count()].toInt() }
    }

    fun getDoctorLoad(limit: Int = 5): List<Pair<String, Int>> = transaction {
        AppointmentsTable
            .innerJoin(DoctorsTable, { AppointmentsTable.doctorId }, { DoctorsTable.doctorId })
            .slice(DoctorsTable.fullName, AppointmentsTable.appointmentId.count())
            .selectAll()
            .groupBy(DoctorsTable.fullName)
            .orderBy(AppointmentsTable.appointmentId.count() to SortOrder.DESC)
            .limit(limit)
            .map { it[DoctorsTable.fullName] to it[AppointmentsTable.appointmentId.count()].toInt() }
    }

    fun getStats(): AdminStats = transaction {
        val totalUsers   = UsersTable.selectAll().count()
        val totalDoctors = DoctorsTable.select { DoctorsTable.isActive eq true }.count()
        val today        = java.time.LocalDate.now()
        val weekAgo      = today.minusDays(6)

        val todayCount = AppointmentsTable
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .select { SchedulesTable.slotDate eq today }
            .count()

        val weekCount = AppointmentsTable
            .innerJoin(SchedulesTable, { AppointmentsTable.scheduleId }, { SchedulesTable.scheduleId })
            .select { SchedulesTable.slotDate greaterEq weekAgo and (SchedulesTable.slotDate lessEq today) }
            .count()

        val topSpecs = AppointmentsTable
            .innerJoin(DoctorsTable, { AppointmentsTable.doctorId }, { DoctorsTable.doctorId })
            .innerJoin(SpecializationsTable, { DoctorsTable.specializationId }, { SpecializationsTable.specializationId })
            .slice(SpecializationsTable.name, AppointmentsTable.appointmentId.count())
            .selectAll()
            .groupBy(SpecializationsTable.name)
            .orderBy(AppointmentsTable.appointmentId.count() to SortOrder.DESC)
            .limit(5)
            .map { it[SpecializationsTable.name] to it[AppointmentsTable.appointmentId.count()].toInt() }

        AdminStats(totalUsers, totalDoctors, todayCount, weekCount, topSpecs)
    }

    fun getAiStats(): Triple<Long, Long, List<Pair<String, Int>>> = transaction {
        val total = SymptomsRequestsTable.selectAll().count()
        val cutoff = OffsetDateTime.now().minusDays(30)
        val last30 = SymptomsRequestsTable
            .select { SymptomsRequestsTable.createdAt greaterEq cutoff }
            .count()
        val urgency = AiResponsesTable
            .slice(AiResponsesTable.urgency, AiResponsesTable.responseId.count())
            .selectAll()
            .groupBy(AiResponsesTable.urgency)
            .map { it[AiResponsesTable.urgency] to it[AiResponsesTable.responseId.count()].toInt() }
        Triple(total, last30, urgency)
    }
}

data class AdminStats(
    val totalUsers: Long,
    val totalDoctors: Long,
    val appointmentsToday: Long,
    val appointmentsWeek: Long,
    val topSpecializations: List<Pair<String, Int>>
)

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

class DocumentRepositoryImpl {

    data class Document(
        val documentId:  UUID,
        val userId:      UUID,
        val fileName:    String,
        val fileType:    String,
        val filePath:    String,
        val fileSize:    Long,
        val category:    String,
        val description: String?,
        val createdAt:   OffsetDateTime
    )

    fun findByUser(userId: UUID): List<Document> = transaction {
        UserDocumentsTable
            .select { UserDocumentsTable.userId eq userId }
            .orderBy(UserDocumentsTable.createdAt to SortOrder.DESC)
            .map { it.toDocument() }
    }

    fun findById(documentId: UUID, userId: UUID): Document? = transaction {
        UserDocumentsTable
            .select {
                (UserDocumentsTable.documentId eq documentId) and
                        (UserDocumentsTable.userId eq userId)
            }
            .singleOrNull()
            ?.toDocument()
    }

    fun create(
        userId: UUID, fileName: String, fileType: String,
        filePath: String, fileSize: Long, category: String, description: String?
    ): UUID = transaction {
        val id = UUID.randomUUID()
        UserDocumentsTable.insert {
            it[UserDocumentsTable.documentId]  = id
            it[UserDocumentsTable.userId]      = userId
            it[UserDocumentsTable.fileName]    = fileName
            it[UserDocumentsTable.fileType]    = fileType
            it[UserDocumentsTable.filePath]    = filePath
            it[UserDocumentsTable.fileSize]    = fileSize
            it[UserDocumentsTable.category]    = category
            it[UserDocumentsTable.description] = description
            it[UserDocumentsTable.createdAt]   = OffsetDateTime.now()
        }
        id
    }

    fun delete(documentId: UUID, userId: UUID): String? = transaction {
        val row = UserDocumentsTable
            .select {
                (UserDocumentsTable.documentId eq documentId) and
                        (UserDocumentsTable.userId eq userId)
            }
            .singleOrNull() ?: return@transaction null
        val path = row[UserDocumentsTable.filePath]
        UserDocumentsTable.deleteWhere {
            (UserDocumentsTable.documentId eq documentId) and
                    (UserDocumentsTable.userId eq userId)
        }
        path
    }

    private fun ResultRow.toDocument() = Document(
        documentId  = this[UserDocumentsTable.documentId],
        userId      = this[UserDocumentsTable.userId],
        fileName    = this[UserDocumentsTable.fileName],
        fileType    = this[UserDocumentsTable.fileType],
        filePath    = this[UserDocumentsTable.filePath],
        fileSize    = this[UserDocumentsTable.fileSize],
        category    = this[UserDocumentsTable.category],
        description = this[UserDocumentsTable.description],
        createdAt   = this[UserDocumentsTable.createdAt]
    )
}

class RefreshTokenRepositoryImpl {

    fun create(userId: UUID): String = transaction {
        val raw = java.util.UUID.randomUUID().toString().replace("-", "") +
                  java.util.UUID.randomUUID().toString().replace("-", "")
        RefreshTokensTable.insert {
            it[RefreshTokensTable.userId]    = userId
            it[RefreshTokensTable.token]     = raw
            it[RefreshTokensTable.expiresAt] = OffsetDateTime.now().plusDays(30)
            it[RefreshTokensTable.createdAt] = OffsetDateTime.now()
        }
        raw
    }

    fun findValidUserId(raw: String): UUID? = transaction {
        RefreshTokensTable
            .select {
                (RefreshTokensTable.token     eq raw) and
                (RefreshTokensTable.revoked   eq false) and
                (RefreshTokensTable.expiresAt greater OffsetDateTime.now())
            }
            .singleOrNull()
            ?.get(RefreshTokensTable.userId)
    }

    fun revoke(raw: String): Unit = transaction {
        RefreshTokensTable.update({ RefreshTokensTable.token eq raw }) {
            it[revoked] = true
        }
    }

    fun revokeAllForUser(userId: UUID): Unit = transaction {
        RefreshTokensTable.update({ RefreshTokensTable.userId eq userId }) {
            it[revoked] = true
        }
    }
}

class PasswordResetTokenRepositoryImpl {

    fun create(userId: UUID): String = transaction {
        PasswordResetTokensTable.update({
            (PasswordResetTokensTable.userId eq userId) and
            (PasswordResetTokensTable.used   eq false)
        }) { it[used] = true }

        val raw = buildString {
            val rng = java.security.SecureRandom()
            val bytes = ByteArray(32)
            rng.nextBytes(bytes)
            bytes.forEach { append("%02x".format(it)) }
        }
        PasswordResetTokensTable.insert {
            it[PasswordResetTokensTable.userId]    = userId
            it[PasswordResetTokensTable.token]     = raw
            it[PasswordResetTokensTable.expiresAt] = OffsetDateTime.now().plusHours(1)
            it[PasswordResetTokensTable.createdAt] = OffsetDateTime.now()
        }
        raw
    }

    fun findValidUserId(raw: String): UUID? = transaction {
        PasswordResetTokensTable
            .select {
                (PasswordResetTokensTable.token     eq raw) and
                (PasswordResetTokensTable.used      eq false) and
                (PasswordResetTokensTable.expiresAt greater OffsetDateTime.now())
            }
            .singleOrNull()
            ?.get(PasswordResetTokensTable.userId)
    }

    fun markUsed(raw: String): Unit = transaction {
        PasswordResetTokensTable.update({ PasswordResetTokensTable.token eq raw }) {
            it[used] = true
        }
    }
}