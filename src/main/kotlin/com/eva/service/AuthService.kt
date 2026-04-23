package com.eva.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.eva.data.repository.RefreshTokenRepositoryImpl
import com.eva.data.repository.UserRepositoryImpl
import com.eva.plugins.ConflictException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import java.util.Date
import java.util.UUID

class AuthService(
    private val userRepository: UserRepositoryImpl,
    private val refreshTokenRepository: RefreshTokenRepositoryImpl,
    private val secret: String,
    private val issuer: String,
    private val audience: String,
    private val expirationMs: Long
) {
    fun register(
        fullName: String,
        email: String,
        phone: String?,
        password: String,
        consentMedical: Boolean,
        consentAi: Boolean
    ): UUID {
        if (userRepository.existsByEmail(email))
            throw ConflictException("Пользователь с таким email уже существует")
        if (phone != null && userRepository.existsByPhone(phone))
            throw ConflictException("Пользователь с таким телефоном уже существует")

        val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
        return try {
            userRepository.create(
                fullName       = fullName,
                email          = email,
                phone          = phone,
                passwordHash   = hash,
                consentMedical = consentMedical,
                consentAi      = consentAi
            )
        } catch (e: ExposedSQLException) {
            // sqlState 23505 = unique_violation (PostgreSQL)
            if (e.sqlState?.startsWith("23") == true)
                throw IllegalArgumentException("Пользователь с таким email или телефоном уже существует")
            throw e
        }
    }

    fun login(email: String, password: String): LoginResult {
        val user = userRepository.findByEmail(email)
            ?: throw IllegalArgumentException("Неверный email или пароль")

        if (!user.isActive)
            throw IllegalArgumentException("Аккаунт заблокирован")

        val match = BCrypt.verifyer()
            .verify(password.toCharArray(), user.passwordHash)

        if (!match.verified)
            throw IllegalArgumentException("Неверный email или пароль")

        userRepository.updateLastLogin(user.userId)
        val accessToken  = generateToken(user.userId, user.roleName)
        val refreshToken = refreshTokenRepository.create(user.userId)
        return LoginResult(
            token        = accessToken,
            refreshToken = refreshToken,
            userId       = user.userId,
            fullName     = user.fullName,
            roleName     = user.roleName
        )
    }

    fun refresh(rawRefreshToken: String): RefreshResult? {
        val userId = refreshTokenRepository.findValidUserId(rawRefreshToken) ?: return null
        val user   = userRepository.findById(userId) ?: return null
        refreshTokenRepository.revoke(rawRefreshToken)
        val newAccessToken  = generateToken(userId, user.roleName)
        val newRefreshToken = refreshTokenRepository.create(userId)
        return RefreshResult(newAccessToken, newRefreshToken)
    }

    data class LoginResult(
        val token: String,
        val refreshToken: String,
        val userId: UUID,
        val fullName: String,
        val roleName: String
    )

    data class RefreshResult(
        val accessToken: String,
        val refreshToken: String
    )

    fun generateToken(userId: UUID, role: String): String =
        JWT.create()
            .withAudience(audience)
            .withIssuer(issuer)
            .withClaim("userId", userId.toString())
            .withClaim("role", role)
            .withExpiresAt(Date(System.currentTimeMillis() + expirationMs))
            .sign(Algorithm.HMAC256(secret))
}