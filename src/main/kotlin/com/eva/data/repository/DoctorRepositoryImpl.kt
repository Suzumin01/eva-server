package com.eva.data.repository

import com.eva.data.tables.*
import com.eva.domain.models.Clinic
import com.eva.domain.models.Doctor
import com.eva.domain.models.DoctorReview
import org.jetbrains.exposed.sql.*
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
