package com.eva.data.repository

import com.eva.data.tables.FcmTokensTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

class FcmTokenRepositoryImpl {

    fun saveToken(userId: UUID, token: String, deviceId: String? = null) {
        transaction {
            val existing = FcmTokensTable
                .select { FcmTokensTable.token eq token }
                .singleOrNull()

            if (existing != null) {
                FcmTokensTable.update({ FcmTokensTable.token eq token }) {
                    it[FcmTokensTable.userId]    = userId
                    it[FcmTokensTable.isActive]  = true
                    it[FcmTokensTable.updatedAt] = OffsetDateTime.now()
                }
            } else {
                FcmTokensTable.insert {
                    it[FcmTokensTable.userId]    = userId
                    it[FcmTokensTable.token]     = token
                    it[FcmTokensTable.deviceId]  = deviceId
                    it[FcmTokensTable.platform]  = "android"
                    it[FcmTokensTable.isActive]  = true
                    it[FcmTokensTable.createdAt] = OffsetDateTime.now()
                    it[FcmTokensTable.updatedAt] = OffsetDateTime.now()
                }
            }
        }
    }

    fun getActiveTokens(userId: UUID): List<String> = transaction {
        FcmTokensTable
            .select { (FcmTokensTable.userId eq userId) and (FcmTokensTable.isActive eq true) }
            .map { it[FcmTokensTable.token] }
    }

    fun deactivateToken(token: String, userId: UUID) {
        transaction {
            FcmTokensTable.update({
                (FcmTokensTable.token eq token) and (FcmTokensTable.userId eq userId)
            }) {
                it[isActive]  = false
                it[updatedAt] = OffsetDateTime.now()
            }
        }
    }

    // (FcmService: устаревший токен, отклонённый Firebase)
    internal fun deactivateTokenInternal(token: String) {
        transaction {
            FcmTokensTable.update({ FcmTokensTable.token eq token }) {
                it[isActive]  = false
                it[updatedAt] = OffsetDateTime.now()
            }
        }
    }

    fun deactivateAllTokens(userId: UUID) {
        transaction {
            FcmTokensTable.update({ FcmTokensTable.userId eq userId }) {
                it[isActive]  = false
                it[updatedAt] = OffsetDateTime.now()
            }
        }
    }
}