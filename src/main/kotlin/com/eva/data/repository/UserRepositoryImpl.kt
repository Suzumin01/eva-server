package com.eva.data.repository

import com.eva.data.tables.RolesTable
import com.eva.data.tables.UsersTable
import com.eva.domain.models.User
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class UserRepositoryImpl {

    fun findByEmail(email: String): User? = transaction {
        (UsersTable innerJoin RolesTable)
            .select { UsersTable.email.lowerCase() eq email.lowercase() }
            .singleOrNull()
            ?.toUser()
    }

    fun findById(userId: UUID): User? = transaction {
        (UsersTable innerJoin RolesTable)
            .select { UsersTable.userId eq userId }
            .singleOrNull()
            ?.toUser()
    }

    fun create(
        fullName: String,
        email: String,
        phone: String?,
        passwordHash: String,
        roleId: Short = 2,   // patient по умолчанию
        consentMedical: Boolean = false,
        consentAi: Boolean = false
    ): UUID = transaction {
        val newId = UUID.randomUUID()
        UsersTable.insert {
            it[UsersTable.userId]       = newId
            it[UsersTable.fullName]     = fullName
            it[UsersTable.email]        = email.lowercase()
            it[UsersTable.phone]        = phone
            it[UsersTable.passwordHash] = passwordHash
            it[UsersTable.roleId]       = roleId
            it[UsersTable.consentMedical] = consentMedical
            it[UsersTable.consentAi]    = consentAi
            if (consentMedical || consentAi) {
                it[UsersTable.consentAt] = OffsetDateTime.now()
            }
        }
        newId
    }

    fun updateProfile(userId: UUID, fullName: String?, phone: String?): Boolean = transaction {
        UsersTable.update({ UsersTable.userId eq userId }) { row ->
            fullName?.let { row[UsersTable.fullName] = it.trim() }
            phone?.let    { row[UsersTable.phone]    = it.trim().ifBlank { null } }
            row[UsersTable.updatedAt] = OffsetDateTime.now()
        } > 0
    }

    fun updateLastLogin(userId: UUID) = transaction {
        UsersTable.update({ UsersTable.userId eq userId }) {
            it[lastLoginAt] = OffsetDateTime.now()
            it[updatedAt]   = OffsetDateTime.now()
        }
    }

    fun existsByEmail(email: String): Boolean = transaction {
        UsersTable.select { UsersTable.email.lowerCase() eq email.lowercase() }.count() > 0
    }

    fun existsByPhone(phone: String): Boolean = transaction {
        UsersTable.select { UsersTable.phone eq phone }.count() > 0
    }

    fun deactivate(userId: UUID): Boolean = transaction {
        UsersTable.update({ UsersTable.userId eq userId }) {
            it[isActive]  = false
            it[updatedAt] = OffsetDateTime.now()
        } > 0
    }

    private fun ResultRow.toUser() = User(
        userId       = this[UsersTable.userId],
        fullName     = this[UsersTable.fullName],
        email        = this[UsersTable.email],
        phone        = this[UsersTable.phone],
        passwordHash = this[UsersTable.passwordHash],
        roleId       = this[UsersTable.roleId],
        roleName     = this[RolesTable.roleName],
        isActive     = this[UsersTable.isActive],
        consentMedical = this[UsersTable.consentMedical],
        consentAi    = this[UsersTable.consentAi],
        lastLoginAt  = this[UsersTable.lastLoginAt],
        createdAt    = this[UsersTable.createdAt]
    )
}