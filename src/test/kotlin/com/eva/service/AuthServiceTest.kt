package com.eva.service

import at.favre.lib.crypto.bcrypt.BCrypt
import com.auth0.jwt.JWT
import com.eva.data.repository.PasswordResetTokenRepositoryImpl
import com.eva.data.repository.RefreshTokenRepositoryImpl
import com.eva.data.repository.UserRepositoryImpl
import com.eva.domain.models.User
import com.eva.plugins.ConflictException
import io.mockk.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {

    private val userRepo    = mockk<UserRepositoryImpl>()
    private val refreshRepo = mockk<RefreshTokenRepositoryImpl>()
    private val resetRepo   = mockk<PasswordResetTokenRepositoryImpl>()

    private val SECRET       = "test-secret-minimum-32-chars-xxxx"
    private val ISSUER       = "eva-backend"
    private val AUDIENCE     = "eva-mobile-client"
    private val EXPIRATION   = 3_600_000L

    private lateinit var service: AuthService

    @BeforeEach
    fun setUp() {
        clearAllMocks()
        service = AuthService(
            userRepository          = userRepo,
            refreshTokenRepository  = refreshRepo,
            passwordResetRepository = resetRepo,
            secret                  = SECRET,
            issuer                  = ISSUER,
            audience                = AUDIENCE,
            expirationMs            = EXPIRATION
        )
    }

    @Test
    fun `register with valid data returns UUID`() {
        val expectedId = UUID.randomUUID()
        every { userRepo.existsByEmail(any()) } returns false
        every { userRepo.existsByPhone(any()) } returns false
        every { userRepo.create(any(), any(), any(), any(), any(), any(), any()) } returns expectedId

        val result = service.register(
            fullName       = "Иван Иванов",
            email          = "ivan@test.com",
            phone          = "+79001234567",
            password       = "securePass1",
            consentMedical = true,
            consentAi      = true
        )

        assertEquals(expectedId, result)
        verify { userRepo.create(any(), "ivan@test.com", "+79001234567", any(), any(), true, true) }
    }

    @Test
    fun `register with duplicate email throws ConflictException`() {
        every { userRepo.existsByEmail("ivan@test.com") } returns true

        assertThrows<ConflictException> {
            service.register("Иван Иванов", "ivan@test.com", null, "securePass1", true, true)
        }
        verify(exactly = 0) { userRepo.create(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `register with duplicate phone throws ConflictException`() {
        every { userRepo.existsByEmail(any()) } returns false
        every { userRepo.existsByPhone("+79001234567") } returns true

        assertThrows<ConflictException> {
            service.register("Иван Иванов", "ivan@test.com", "+79001234567", "securePass1", true, true)
        }
        verify(exactly = 0) { userRepo.create(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `login with correct password returns LoginResult with token`() {
        val userId      = UUID.randomUUID()
        val rawPassword = "correctPass1"
        val hash        = BCrypt.withDefaults().hashToString(4, rawPassword.toCharArray())

        every { userRepo.findByEmail("user@test.com") } returns buildUser(userId, hash)
        every { userRepo.updateLastLogin(userId) } returns 1
        every { refreshRepo.create(userId) } returns "refresh-token-abc"

        val result = service.login("user@test.com", rawPassword)

        assertEquals(userId, result.userId)
        assertNotNull(result.token)
        assertEquals("refresh-token-abc", result.refreshToken)
        assertEquals("patient", result.roleName)
        verify { userRepo.updateLastLogin(userId) }
    }

    @Test
    fun `login with wrong password throws IllegalArgumentException`() {
        val hash = BCrypt.withDefaults().hashToString(4, "correctPass".toCharArray())
        every { userRepo.findByEmail(any()) } returns buildUser(passwordHash = hash)

        assertThrows<IllegalArgumentException> {
            service.login("user@test.com", "wrongPass999")
        }
        verify(exactly = 0) { userRepo.updateLastLogin(any()) }
    }

    @Test
    fun `login with unknown email throws IllegalArgumentException`() {
        every { userRepo.findByEmail(any()) } returns null

        assertThrows<IllegalArgumentException> {
            service.login("nobody@test.com", "somePass123")
        }
    }

    @Test
    fun `login with inactive account throws IllegalArgumentException`() {
        val hash = BCrypt.withDefaults().hashToString(4, "pass123".toCharArray())
        every { userRepo.findByEmail(any()) } returns buildUser(isActive = false, passwordHash = hash)

        val ex = assertThrows<IllegalArgumentException> {
            service.login("user@test.com", "pass123")
        }
        assertTrue(ex.message!!.contains("заблокирован", ignoreCase = true))
    }

    @Test
    fun `refresh with valid token returns new access and refresh tokens`() {
        val userId = UUID.randomUUID()
        every { refreshRepo.findValidUserId("valid-refresh") } returns userId
        every { userRepo.findById(userId) } returns buildUser(userId)
        every { refreshRepo.revoke("valid-refresh") } answers { Unit }
        every { refreshRepo.create(userId) } returns "new-refresh-token"

        val result = service.refresh("valid-refresh")

        assertNotNull(result)
        assertNotNull(result.accessToken)
        assertEquals("new-refresh-token", result.refreshToken)
        verify { refreshRepo.revoke("valid-refresh") }
    }

    @Test
    fun `refresh with expired or unknown token returns null`() {
        every { refreshRepo.findValidUserId("bad-token") } returns null

        assertNull(service.refresh("bad-token"))
        verify(exactly = 0) { refreshRepo.create(any()) }
    }

    @Test
    fun `requestPasswordReset for existing email returns reset token`() {
        val userId = UUID.randomUUID()
        every { userRepo.findByEmail("user@test.com") } returns buildUser(userId)
        every { resetRepo.create(userId) } returns "secure-reset-token-64chars"

        val token = service.requestPasswordReset("user@test.com")

        assertEquals("secure-reset-token-64chars", token)
        verify { resetRepo.create(userId) }
    }

    @Test
    fun `requestPasswordReset for unknown email returns null without creating token`() {
        every { userRepo.findByEmail(any()) } returns null

        assertNull(service.requestPasswordReset("nobody@test.com"))
        verify(exactly = 0) { resetRepo.create(any()) }
    }

    @Test
    fun `resetPassword with valid token updates hash and revokes refresh tokens`() {
        val userId = UUID.randomUUID()
        every { resetRepo.findValidUserId("valid-reset-token") } returns userId
        every { userRepo.updatePasswordHash(userId, any()) } returns true
        every { resetRepo.markUsed("valid-reset-token") } answers { Unit }
        every { refreshRepo.revokeAllForUser(userId) } answers { Unit }

        val result = service.resetPassword("valid-reset-token", "newPassword123")

        assertTrue(result)
        verify { userRepo.updatePasswordHash(userId, any()) }
        verify { resetRepo.markUsed("valid-reset-token") }
        verify { refreshRepo.revokeAllForUser(userId) }
    }

    @Test
    fun `resetPassword with invalid or expired token returns false without any changes`() {
        every { resetRepo.findValidUserId("bad-reset-token") } returns null

        assertFalse(service.resetPassword("bad-reset-token", "newPassword123"))
        verify(exactly = 0) { userRepo.updatePasswordHash(any(), any()) }
        verify(exactly = 0) { refreshRepo.revokeAllForUser(any()) }
    }

    @Test
    fun `generateToken produces JWT with correct userId, role, issuer and audience`() {
        val userId = UUID.randomUUID()

        val token   = service.generateToken(userId, "patient")
        val decoded = JWT.decode(token)

        assertEquals(userId.toString(), decoded.getClaim("userId").asString())
        assertEquals("patient",         decoded.getClaim("role").asString())
        assertEquals(ISSUER,            decoded.issuer)
        assertEquals(AUDIENCE,          decoded.audience.first())
        assertNotNull(decoded.expiresAt)
    }

    private fun buildUser(
        userId: UUID = UUID.randomUUID(),
        passwordHash: String = BCrypt.withDefaults().hashToString(4, "password".toCharArray()),
        isActive: Boolean = true
    ) = User(
        userId          = userId,
        fullName        = "Test User",
        email           = "user@test.com",
        phone           = null,
        passwordHash    = passwordHash,
        roleId          = 2,
        roleName        = "patient",
        isActive        = isActive,
        consentMedical  = true,
        consentAi       = true,
        lastLoginAt     = null,
        createdAt       = OffsetDateTime.now(),
        avatarUrl       = null,
        allergies       = null,
        chronicDiseases = null,
        insurancePolicy = null,
        dateOfBirth     = null
    )
}
