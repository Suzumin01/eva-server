package com.eva.api.routes

import com.eva.api.dto.*
import org.slf4j.LoggerFactory
import com.eva.data.repository.FcmTokenRepositoryImpl
import com.eva.data.repository.LogRepositoryImpl
import com.eva.data.repository.RefreshTokenRepositoryImpl
import com.eva.data.repository.UserRepositoryImpl
import com.eva.plugins.getUserId
import com.eva.service.AuthService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

private val authLogger = LoggerFactory.getLogger("com.eva.routes.Auth")

private val AVATAR_DIR: String = run {
    val fromEnv = System.getenv("UPLOAD_DIR")
    val base = if (!fromEnv.isNullOrBlank()) fromEnv else File("uploads").absolutePath
    File("$base/avatars").also { it.mkdirs() }.absolutePath
}
private const val AVATAR_MAX_BYTES = 5 * 1024 * 1024L // 5 MB

fun Route.authRoutes(
    authService: AuthService,
    userRepository: UserRepositoryImpl,
    fcmTokenRepository: FcmTokenRepositoryImpl,
    logRepository: LogRepositoryImpl,
    refreshTokenRepository: RefreshTokenRepositoryImpl
) {
    route("/auth") {
        registerRoutes(authService, logRepository)
        loginRoutes(authService, logRepository)
        passwordRoutes(authService)
        refreshRoute(authService)
        avatarPublicRoute()

        authenticate("jwt-auth") {
            profileRoutes(userRepository)
            avatarUploadRoute(userRepository)
            fcmTokenRoutes(fcmTokenRepository)
        }
    }
}

private fun Route.registerRoutes(authService: AuthService, logRepository: LogRepositoryImpl) {
    post("/register") {
        val req = call.receive<RegisterRequest>()

        require(req.fullName.trim().length >= 2) { "ФИО должно содержать минимум 2 символа" }
        require(req.password.length >= 8)        { "Пароль должен содержать минимум 8 символов" }
        require(req.email.contains("@"))         { "Некорректный email" }

        val userId = authService.register(
            fullName       = req.fullName.trim(),
            email          = req.email.trim().lowercase(),
            phone          = req.phone?.trim(),
            password       = req.password,
            consentMedical = req.consentMedical,
            consentAi      = req.consentAi
        )

        logRepository.log(
            userId    = userId,
            action    = "USER_REGISTER",
            ipAddress = call.request.origin.remoteHost,
            meta      = buildJsonObject { put("email", req.email) }.toString()
        )

        call.respond(HttpStatusCode.Created, RegisterResponse(userId = userId.toString()))
    }
}

private fun Route.loginRoutes(authService: AuthService, logRepository: LogRepositoryImpl) {
    post("/login") {
        val req    = call.receive<LoginRequest>()
        val result = authService.login(req.email.trim().lowercase(), req.password)

        logRepository.log(
            userId    = result.userId,
            action    = "USER_LOGIN",
            ipAddress = call.request.origin.remoteHost,
            userAgent = call.request.headers["User-Agent"],
            meta      = buildJsonObject { put("success", true) }.toString()
        )

        call.respond(AuthResponse(
            token        = result.token,
            refreshToken = result.refreshToken,
            userId       = result.userId.toString(),
            fullName     = result.fullName,
            role         = result.roleName
        ))
    }
}

private fun Route.passwordRoutes(authService: AuthService) {
    post("/forgot-password") {
        val req   = call.receive<ForgotPasswordRequest>()
        val email = req.email.trim().lowercase()
        val token = authService.requestPasswordReset(email)

        // Не раскрываем факт существования аккаунта независимо от результата
        // В production: отправить email с токеном; для MVP токен возвращается в ответе
        call.respond(ForgotPasswordResponse(
            message    = "Если указанный email зарегистрирован, инструкция по сбросу пароля отправлена",
            resetToken = token
        ))
    }

    post("/reset-password") {
        val req = call.receive<ResetPasswordRequest>()
        require(req.newPassword.length >= 8) { "Пароль должен содержать минимум 8 символов" }

        val ok = authService.resetPassword(req.token, req.newPassword)
        if (!ok) {
            call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Токен недействителен или истёк"))
            return@post
        }
        call.respond(mapOf("message" to "Пароль успешно изменён"))
    }
}

private fun Route.refreshRoute(authService: AuthService) {
    post("/refresh") {
        val req    = call.receive<RefreshRequest>()
        val result = authService.refresh(req.refreshToken)
            ?: return@post call.respond(HttpStatusCode.Unauthorized,
                mapOf("message" to "Refresh token недействителен или истёк"))
        call.respond(RefreshResponse(token = result.accessToken, refreshToken = result.refreshToken))
    }
}

private fun Route.avatarPublicRoute() {
    get("/photo/{userId}") {
        val rawId = call.parameters["userId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest)
        val file = listOf("jpg", "png")
            .map { File("$AVATAR_DIR/$rawId.$it") }
            .firstOrNull { it.exists() }
            ?: return@get call.respond(HttpStatusCode.NotFound)
        val contentType = if (file.extension == "png") ContentType.Image.PNG else ContentType.Image.JPEG
        call.respond(LocalFileContent(file, contentType))
    }
}

private fun Route.profileRoutes(userRepository: UserRepositoryImpl) {
    get("/me") {
        val userId = UUID.fromString(call.getUserId())
        val user   = userRepository.findById(userId)
            ?: return@get call.respond(HttpStatusCode.NotFound)
        call.respond(user.toProfileDto())
    }

    patch("/me") {
        val userId = UUID.fromString(call.getUserId())
        val req    = call.receive<UpdateProfileRequest>()

        if (req.fullName != null) {
            require(req.fullName.trim().length >= 2) { "Имя должно содержать минимум 2 символа" }
        }

        val parsedDob = req.dateOfBirth?.let {
            if (it.isBlank()) null
            else runCatching { java.time.LocalDate.parse(it) }.getOrElse {
                return@patch call.respond(HttpStatusCode.BadRequest,
                    mapOf("message" to "Некорректный формат даты рождения (ожидается YYYY-MM-DD)"))
            }
        }

        val updated = userRepository.updateProfile(
            userId          = userId,
            fullName        = req.fullName,
            phone           = req.phone,
            dateOfBirth     = parsedDob,
            allergies       = req.allergies,
            chronicDiseases = req.chronicDiseases,
            insurancePolicy = req.insurancePolicy,
            clearDateOfBirth = req.dateOfBirth == "",
            clearAllergies   = req.allergies == "",
            clearChronic     = req.chronicDiseases == "",
            clearInsurance   = req.insurancePolicy == ""
        )

        if (!updated) return@patch call.respond(HttpStatusCode.NotFound)

        val user = userRepository.findById(userId)
            ?: return@patch call.respond(HttpStatusCode.InternalServerError)
        call.respond(user.toProfileDto())
    }
}

private fun Route.avatarUploadRoute(userRepository: UserRepositoryImpl) {
    post("/photo") {
        val userId    = UUID.fromString(call.getUserId())
        val multipart = call.receiveMultipart()

        var ext       = ""
        var savedFile: File? = null
        var overLimit = false

        multipart.forEachPart { part ->
            if (part is PartData.FileItem && part.name == "photo") {
                val origName = part.originalFileName ?: ""
                ext = when {
                    origName.endsWith(".jpg",  ignoreCase = true) -> "jpg"
                    origName.endsWith(".jpeg", ignoreCase = true) -> "jpg"
                    origName.endsWith(".png",  ignoreCase = true) -> "png"
                    else -> ""
                }
                if (ext.isNotEmpty()) {
                    val file = File("$AVATAR_DIR/$userId.$ext")
                    var written = 0L
                    part.streamProvider().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                written += read
                                if (written > AVATAR_MAX_BYTES) { overLimit = true; break }
                                output.write(buf, 0, read)
                            }
                        }
                    }
                    if (overLimit) file.delete() else savedFile = file
                }
            }
            part.dispose()
        }

        when {
            overLimit     -> call.respond(HttpStatusCode.PayloadTooLarge,
                mapOf("message" to "Файл слишком большой (макс. 5 МБ)"))
            ext.isEmpty() -> call.respond(HttpStatusCode.BadRequest,
                mapOf("message" to "Поддерживаются только jpg/jpeg/png"))
            savedFile == null -> call.respond(HttpStatusCode.BadRequest,
                mapOf("message" to "Файл не получен (ожидается поле 'photo')"))
            else -> {
                listOf("jpg", "png").filter { it != ext }.forEach {
                    File("$AVATAR_DIR/$userId.$it").delete()
                }
                val avatarUrl = "/api/v1/auth/photo/$userId"
                userRepository.updateAvatarUrl(userId, avatarUrl)
                call.respond(mapOf("avatarUrl" to avatarUrl))
            }
        }
    }
}

private fun Route.fcmTokenRoutes(fcmTokenRepository: FcmTokenRepositoryImpl) {
    post("/fcm-token") {
        val userId = UUID.fromString(call.getUserId())
        val req    = call.receive<RegisterFcmTokenRequest>()

        if (req.token.isBlank())
            return@post call.respond(HttpStatusCode.BadRequest,
                mapOf("message" to "Токен не может быть пустым"))

        fcmTokenRepository.saveToken(userId = userId, token = req.token, deviceId = req.deviceId)
        call.respond(mapOf("message" to "Токен сохранён"))
    }

    delete("/fcm-token") {
        val userId = UUID.fromString(call.getUserId())
        val req    = call.receive<RegisterFcmTokenRequest>()
        fcmTokenRepository.deactivateToken(req.token, userId)
        call.respond(mapOf("message" to "Токен деактивирован"))
    }
}

private fun com.eva.domain.models.User.toProfileDto() = UserProfileDto(
    userId          = userId.toString(),
    fullName        = fullName,
    email           = email,
    phone           = phone,
    role            = roleName,
    isActive        = isActive,
    consentMedical  = consentMedical,
    consentAi       = consentAi,
    avatarUrl       = avatarUrl,
    dateOfBirth     = dateOfBirth?.toString(),
    allergies       = allergies,
    chronicDiseases = chronicDiseases,
    insurancePolicy = insurancePolicy
)