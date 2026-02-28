package com.eva.data.repository

import com.eva.data.tables.*
import com.eva.domain.models.Clinic
import com.eva.domain.models.Doctor
import com.eva.domain.models.DoctorReview
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class DoctorRepositoryImpl {

    fun findAll(
        specializationId: Short? = null,
        clinicId: Int? = null,
        search: String? = null,
        limit: Int = 20,
        offset: Long = 0
    ): List<Doctor> = transaction {
        val query = (DoctorsTable
                innerJoin ClinicsTable
                innerJoin SpecializationsTable)
            .select { DoctorsTable.isActive eq true }

        specializationId?.let { query.andWhere { DoctorsTable.specializationId eq it } }
        clinicId?.let       { query.andWhere { DoctorsTable.clinicId eq it } }
        search?.let {
            query.andWhere {
                DoctorsTable.fullName.lowerCase() like "%${it.lowercase()}%"
            }
        }

        query.orderBy(DoctorsTable.rating to SortOrder.DESC_NULLS_LAST)
            .limit(limit, offset)
            .map { it.toDoctor() }
    }

    fun findById(doctorId: Int): Doctor? = transaction {
        (DoctorsTable innerJoin ClinicsTable innerJoin SpecializationsTable)
            .select { DoctorsTable.doctorId eq doctorId and (DoctorsTable.isActive eq true) }
            .singleOrNull()
            ?.toDoctor()
    }

    fun getReviews(doctorId: Int): List<DoctorReview> = transaction {
        (DoctorReviewsTable innerJoin UsersTable)
            .select { DoctorReviewsTable.doctorId eq doctorId }
            .orderBy(DoctorReviewsTable.createdAt to SortOrder.DESC)
            .map {
                DoctorReview(
                    reviewId    = it[DoctorReviewsTable.reviewId],
                    doctorId    = it[DoctorReviewsTable.doctorId],
                    userId      = it[DoctorReviewsTable.userId],
                    userFullName = it[UsersTable.fullName],
                    rating      = it[DoctorReviewsTable.rating],
                    comment     = it[DoctorReviewsTable.comment],
                    createdAt   = it[DoctorReviewsTable.createdAt]
                )
            }
    }

    /** Проверяет, есть ли у пользователя завершённый приём с этим врачом */
    fun hasCompletedAppointment(doctorId: Int, userId: UUID): Boolean = transaction {
        AppointmentsTable
            .select {
                (AppointmentsTable.doctorId eq doctorId) and
                        (AppointmentsTable.userId   eq userId)   and
                        (AppointmentsTable.status   eq "completed")
            }
            .count() > 0
    }

    /** Проверяет, уже ли оставил пользователь отзыв этому врачу */
    fun hasReviewed(doctorId: Int, userId: UUID): Boolean = transaction {
        DoctorReviewsTable
            .select {
                (DoctorReviewsTable.doctorId eq doctorId) and
                        (DoctorReviewsTable.userId   eq userId)
            }
            .count() > 0
    }

    fun addReview(doctorId: Int, userId: UUID, rating: Short, comment: String?): UUID = transaction {
        val id = UUID.randomUUID()
        DoctorReviewsTable.insert {
            it[DoctorReviewsTable.reviewId]  = id
            it[DoctorReviewsTable.doctorId]  = doctorId
            it[DoctorReviewsTable.userId]    = userId
            it[DoctorReviewsTable.rating]    = rating
            it[DoctorReviewsTable.comment]   = comment
            it[DoctorReviewsTable.createdAt] = OffsetDateTime.now()
            it[DoctorReviewsTable.updatedAt] = OffsetDateTime.now()
        }
        id
    }

    fun updateReview(reviewId: UUID, userId: UUID, rating: Short, comment: String?): Boolean = transaction {
        DoctorReviewsTable.update({
            (DoctorReviewsTable.reviewId eq reviewId) and
                    (DoctorReviewsTable.userId   eq userId)
        }) {
            it[DoctorReviewsTable.rating]  = rating
            it[DoctorReviewsTable.comment] = comment
        } > 0
    }

    fun deleteReview(reviewId: UUID, userId: UUID): Int? = transaction {
        val row = DoctorReviewsTable
            .select { (DoctorReviewsTable.reviewId eq reviewId) and (DoctorReviewsTable.userId eq userId) }
            .singleOrNull() ?: return@transaction null
        val doctorId = row[DoctorReviewsTable.doctorId]
        DoctorReviewsTable.deleteWhere {
            (DoctorReviewsTable.reviewId eq reviewId) and (DoctorReviewsTable.userId eq userId)
        }
        doctorId
    }

    fun recalculateRating(doctorId: Int) = transaction {
        val reviews = DoctorReviewsTable
            .select { DoctorReviewsTable.doctorId eq doctorId }
            .toList()
        val count = reviews.size
        val avg   = if (count == 0) null
        else reviews.sumOf { it[DoctorReviewsTable.rating].toDouble() } / count
        DoctorsTable.update({ DoctorsTable.doctorId eq doctorId }) {
            it[rating]       = avg?.let { v -> java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP) }
            it[reviewsCount] = count
            it[updatedAt]    = java.time.OffsetDateTime.now()
        }
    }

    private fun ResultRow.toDoctor() = Doctor(
        doctorId           = this[DoctorsTable.doctorId],
        fullName           = this[DoctorsTable.fullName],
        clinicId           = this[ClinicsTable.clinicId],
        clinicName         = this[ClinicsTable.clinicName],
        clinicAddress      = this[ClinicsTable.address],
        specializationId   = this[SpecializationsTable.specializationId],
        specializationName = this[SpecializationsTable.name],
        bio                = this[DoctorsTable.bio],
        photoUrl           = this[DoctorsTable.photoUrl],
        experienceYears    = this[DoctorsTable.experienceYears],
        rating             = this[DoctorsTable.rating],
        reviewsCount       = this[DoctorsTable.reviewsCount],
        isActive           = this[DoctorsTable.isActive]
    )
}

class ClinicRepositoryImpl {
    fun findAll(): List<Clinic> = transaction {
        ClinicsTable.select { ClinicsTable.isActive eq true }
            .map {
                Clinic(
                    clinicId   = it[ClinicsTable.clinicId],
                    clinicName = it[ClinicsTable.clinicName],
                    address    = it[ClinicsTable.address],
                    phone      = it[ClinicsTable.phone],
                    website    = it[ClinicsTable.website],
                    latitude   = it[ClinicsTable.latitude],
                    longitude  = it[ClinicsTable.longitude]
                )
            }
    }
}