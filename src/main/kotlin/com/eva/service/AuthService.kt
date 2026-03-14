package com.eva.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.eva.data.repository.UserRepositoryImpl
import java.util.Date
import java.util.UUID

class AuthService(
    private val userRepository: UserRepositoryImpl,
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
            throw IllegalArgumentException("Пользователь с таким email уже существует")
        if (phone != null && userRepository.existsByPhone(phone))
            throw IllegalArgumentException("Пользователь с таким телефоном уже существует")

        val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
        return userRepository.create(
            fullName       = fullName,
            email          = email,
            phone          = phone,
            passwordHash   = hash,
            consentMedical = consentMedical,
            consentAi      = consentAi
        )
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
        val token = generateToken(user.userId, user.roleName)
        return LoginResult(token = token, userId = user.userId, fullName = user.fullName, roleName = user.roleName)
    }

    data class LoginResult(
        val token: String,
        val userId: UUID,
        val fullName: String,
        val roleName: String
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