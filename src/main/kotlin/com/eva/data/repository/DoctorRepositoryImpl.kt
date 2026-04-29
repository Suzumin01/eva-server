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

    fun countAll(
        specializationId: Short? = null,
        clinicId: Int? = null,
        search: String? = null
    ): Long = transaction {
        val query = (DoctorsTable innerJoin ClinicsTable innerJoin SpecializationsTable)
            .slice(DoctorsTable.doctorId.count())
            .select { DoctorsTable.isActive eq true }
        specializationId?.let { query.andWhere { DoctorsTable.specializationId eq it } }
        clinicId?.let       { query.andWhere { DoctorsTable.clinicId eq it } }
        search?.let {
            query.andWhere { DoctorsTable.fullName.lowerCase() like "%${it.lowercase()}%" }
        }
        query.single()[DoctorsTable.doctorId.count()]
    }

    fun findById(doctorId: Int): Doctor? = transaction {
        (DoctorsTable innerJoin ClinicsTable innerJoin SpecializationsTable)
            .select { DoctorsTable.doctorId eq doctorId and (DoctorsTable.isActive eq true) }
            .singleOrNull()
            ?.toDoctor()
    }

    fun getReviews(doctorId: Int): List<DoctorReview> = transaction {
        DoctorReviewsTable
            .innerJoin(UsersTable,   { DoctorReviewsTable.userId   }, { UsersTable.userId     })
            .innerJoin(DoctorsTable, { DoctorReviewsTable.doctorId }, { DoctorsTable.doctorId })
            .select { DoctorReviewsTable.doctorId eq doctorId and (DoctorReviewsTable.isHidden eq false) }
            .orderBy(DoctorReviewsTable.createdAt to SortOrder.DESC)
            .map { it.toReview() }
    }

    fun createDoctor(
        fullName: String,
        clinicId: Int,
        specializationId: Short,
        bio: String?,
        photoUrl: String?,
        experienceYears: Short?
    ): Int = transaction {
        DoctorsTable.insert {
            it[DoctorsTable.fullName]         = fullName
            it[DoctorsTable.clinicId]         = clinicId
            it[DoctorsTable.specializationId] = specializationId
            it[DoctorsTable.bio]              = bio
            it[DoctorsTable.photoUrl]         = photoUrl
            it[DoctorsTable.experienceYears]  = experienceYears
            it[DoctorsTable.isActive]         = true
            it[DoctorsTable.createdAt]        = OffsetDateTime.now()
            it[DoctorsTable.updatedAt]        = OffsetDateTime.now()
        }[DoctorsTable.doctorId]
    }

    fun updateDoctor(
        doctorId: Int,
        fullName: String?,
        clinicId: Int?,
        specializationId: Short?,
        bio: String?,
        photoUrl: String?,
        experienceYears: Short?
    ): Boolean = transaction {
        DoctorsTable.update({ DoctorsTable.doctorId eq doctorId }) { row ->
            fullName?.let         { row[DoctorsTable.fullName] = it }
            clinicId?.let         { row[DoctorsTable.clinicId] = it }
            specializationId?.let { row[DoctorsTable.specializationId] = it }
            bio?.let              { row[DoctorsTable.bio] = it.ifBlank { null } }
            photoUrl?.let         { row[DoctorsTable.photoUrl] = it.ifBlank { null } }
            experienceYears?.let  { row[DoctorsTable.experienceYears] = it }
            row[DoctorsTable.updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun deactivateDoctor(doctorId: Int): Boolean = transaction {
        DoctorsTable.update({ DoctorsTable.doctorId eq doctorId }) {
            it[isActive]  = false
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun linkUser(doctorId: Int, userId: UUID): Boolean = transaction {
        DoctorsTable.update({ DoctorsTable.doctorId eq doctorId }) {
            it[DoctorsTable.userId] = userId
            it[updatedAt]          = OffsetDateTime.now()
        } > 0
    }

    fun findAllReviews(doctorId: Int? = null, isHidden: Boolean? = null): List<DoctorReview> = transaction {
        val query = DoctorReviewsTable
            .innerJoin(UsersTable,   { DoctorReviewsTable.userId   }, { UsersTable.userId     })
            .innerJoin(DoctorsTable, { DoctorReviewsTable.doctorId }, { DoctorsTable.doctorId })
            .selectAll()
        doctorId?.let { query.andWhere { DoctorReviewsTable.doctorId eq it } }
        isHidden?.let { query.andWhere { DoctorReviewsTable.isHidden eq it } }
        query.orderBy(DoctorReviewsTable.createdAt to SortOrder.DESC).map { it.toReview() }
    }

    fun hideReview(reviewId: UUID, hidden: Boolean): Boolean = transaction {
        DoctorReviewsTable.update({ DoctorReviewsTable.reviewId eq reviewId }) {
            it[isHidden]  = hidden
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun deleteReviewAdmin(reviewId: UUID): Boolean = transaction {
        val row = DoctorReviewsTable.select { DoctorReviewsTable.reviewId eq reviewId }
            .singleOrNull() ?: return@transaction false
        val doctorId = row[DoctorReviewsTable.doctorId]
        DoctorReviewsTable.deleteWhere { DoctorReviewsTable.reviewId eq reviewId }
        recalculateRatingInTransaction(doctorId)
        true
    }

    private fun ResultRow.toReview() = DoctorReview(
        reviewId     = this[DoctorReviewsTable.reviewId],
        doctorId     = this[DoctorReviewsTable.doctorId],
        doctorName   = this[DoctorsTable.fullName],
        userId       = this[DoctorReviewsTable.userId],
        userFullName = this[UsersTable.fullName],
        rating       = this[DoctorReviewsTable.rating],
        comment      = this[DoctorReviewsTable.comment],
        isHidden     = this[DoctorReviewsTable.isHidden],
        createdAt    = this[DoctorReviewsTable.createdAt]
    )

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
        recalculateRatingInTransaction(doctorId)
        id
    }

    fun updateReview(reviewId: UUID, userId: UUID, rating: Short, comment: String?): Boolean = transaction {
        val row = DoctorReviewsTable
            .select { (DoctorReviewsTable.reviewId eq reviewId) and (DoctorReviewsTable.userId eq userId) }
            .singleOrNull() ?: return@transaction false
        val doctorId = row[DoctorReviewsTable.doctorId]
        DoctorReviewsTable.update({
            (DoctorReviewsTable.reviewId eq reviewId) and
                    (DoctorReviewsTable.userId   eq userId)
        }) {
            it[DoctorReviewsTable.rating]    = rating
            it[DoctorReviewsTable.comment]   = comment
            it[DoctorReviewsTable.updatedAt] = OffsetDateTime.now()
        }
        recalculateRatingInTransaction(doctorId)
        true
    }

    fun deleteReview(reviewId: UUID, userId: UUID): Int? = transaction {
        val row = DoctorReviewsTable
            .select { (DoctorReviewsTable.reviewId eq reviewId) and (DoctorReviewsTable.userId eq userId) }
            .singleOrNull() ?: return@transaction null
        val doctorId = row[DoctorReviewsTable.doctorId]
        DoctorReviewsTable.deleteWhere {
            (DoctorReviewsTable.reviewId eq reviewId) and (DoctorReviewsTable.userId eq userId)
        }
        recalculateRatingInTransaction(doctorId)
        doctorId
    }

    fun findIdByUserId(userId: UUID): Int? = transaction {
        DoctorsTable.select { DoctorsTable.userId eq userId and (DoctorsTable.isActive eq true) }
            .singleOrNull()?.get(DoctorsTable.doctorId)
    }

    // Вызывается внутри существующей транзакции (addReview / deleteReview)
    private fun recalculateRatingInTransaction(doctorId: Int) {
        val row = DoctorReviewsTable
            .slice(DoctorReviewsTable.rating.avg(), DoctorReviewsTable.reviewId.count())
            .select { DoctorReviewsTable.doctorId eq doctorId }
            .single()

        val count = row[DoctorReviewsTable.reviewId.count()].toInt()
        val avg   = row[DoctorReviewsTable.rating.avg()]

        DoctorsTable.update({ DoctorsTable.doctorId eq doctorId }) {
            it[rating]       = avg?.setScale(2, java.math.RoundingMode.HALF_UP)
            it[reviewsCount] = count
            it[updatedAt]    = java.time.OffsetDateTime.now()
        }
    }

    private fun ResultRow.toDoctor() = Doctor(
        doctorId           = this[DoctorsTable.doctorId],
        userId             = this[DoctorsTable.userId],
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
        val clinicStats = DoctorsTable
            .slice(DoctorsTable.clinicId, DoctorsTable.rating.avg(), DoctorsTable.doctorId.count())
            .select { DoctorsTable.isActive eq true }
            .groupBy(DoctorsTable.clinicId)
            .associate { row ->
                row[DoctorsTable.clinicId] to Pair(
                    row[DoctorsTable.rating.avg()],
                    row[DoctorsTable.doctorId.count()].toInt()
                )
            }
        ClinicsTable.select { ClinicsTable.isActive eq true }.map {
            val (avgRating, count) = clinicStats[it[ClinicsTable.clinicId]] ?: Pair(null, 0)
            it.toClinic(avgRating?.setScale(1, java.math.RoundingMode.HALF_UP), count)
        }
    }

    fun findById(clinicId: Int): Clinic? = transaction {
        ClinicsTable.select { ClinicsTable.clinicId eq clinicId }.singleOrNull()?.toClinic()
    }

    fun create(clinicName: String, address: String, phone: String?, website: String?): Int = transaction {
        ClinicsTable.insert {
            it[ClinicsTable.clinicName] = clinicName
            it[ClinicsTable.address]    = address
            it[ClinicsTable.phone]      = phone
            it[ClinicsTable.website]    = website
            it[ClinicsTable.isActive]   = true
            it[ClinicsTable.createdAt]  = OffsetDateTime.now()
            it[ClinicsTable.updatedAt]  = OffsetDateTime.now()
        }[ClinicsTable.clinicId]
    }

    fun update(clinicId: Int, clinicName: String?, address: String?, phone: String?, website: String?): Boolean = transaction {
        ClinicsTable.update({ ClinicsTable.clinicId eq clinicId }) { row ->
            clinicName?.let { row[ClinicsTable.clinicName] = it }
            address?.let    { row[ClinicsTable.address]    = it }
            phone?.let      { row[ClinicsTable.phone]      = it.ifBlank { null } }
            website?.let    { row[ClinicsTable.website]    = it.ifBlank { null } }
            row[ClinicsTable.updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun deactivate(clinicId: Int): Boolean = transaction {
        ClinicsTable.update({ ClinicsTable.clinicId eq clinicId }) {
            it[isActive]  = false
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    private fun ResultRow.toClinic(avgRating: java.math.BigDecimal? = null, doctorsCount: Int = 0) = Clinic(
        clinicId     = this[ClinicsTable.clinicId],
        clinicName   = this[ClinicsTable.clinicName],
        address      = this[ClinicsTable.address],
        phone        = this[ClinicsTable.phone],
        website      = this[ClinicsTable.website],
        latitude     = this[ClinicsTable.latitude],
        longitude    = this[ClinicsTable.longitude],
        rating       = avgRating,
        doctorsCount = doctorsCount
    )
}

class SpecializationRepositoryImpl {
    fun findAll(): List<com.eva.api.dto.SpecializationResponse> = transaction {
        SpecializationsTable
            .selectAll()
            .orderBy(SpecializationsTable.specializationId to SortOrder.ASC)
            .map {
                com.eva.api.dto.SpecializationResponse(
                    specializationId = it[SpecializationsTable.specializationId].toInt(),
                    name             = it[SpecializationsTable.name],
                    description      = it[SpecializationsTable.description]
                )
            }
    }

    fun create(name: String, description: String?): Int = transaction {
        SpecializationsTable.insert {
            it[SpecializationsTable.name]        = name.trim()
            it[SpecializationsTable.description] = description?.trim()
            it[SpecializationsTable.createdAt]   = OffsetDateTime.now()
            it[SpecializationsTable.updatedAt]   = OffsetDateTime.now()
        }[SpecializationsTable.specializationId].toInt()
    }

    fun update(id: Int, name: String?, description: String?): Boolean = transaction {
        SpecializationsTable.update({ SpecializationsTable.specializationId eq id.toShort() }) { row ->
            name?.let        { row[SpecializationsTable.name]        = it.trim() }
            description?.let { row[SpecializationsTable.description] = it.trim().ifBlank { null } }
            row[SpecializationsTable.updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun delete(id: Int): Boolean = transaction {
        SpecializationsTable.deleteWhere { SpecializationsTable.specializationId eq id.toShort() } > 0
    }
}
