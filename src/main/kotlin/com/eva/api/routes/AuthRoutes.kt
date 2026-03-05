package com.eva.api.routes

import com.eva.api.dto.*
import com.eva.data.repository.FcmTokenRepositoryImpl
import com.eva.data.repository.LogRepositoryImpl
import com.eva.data.repository.UserRepositoryImpl
import com.eva.plugins.getUserId
import com.eva.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.authRoutes(
    authService: AuthService,
    userRepository: UserRepositoryImpl,
    fcmTokenRepository: FcmTokenRepositoryImpl,
    logRepository: LogRepositoryImpl
) {
    route("/auth") {

        // POST /api/v1/auth/register
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
                meta      = """{"email":"${req.email}"}"""
            )

            call.respond(HttpStatusCode.Created, RegisterResponse(userId = userId.toString()))
        }

        // POST /api/v1/auth/login
        post("/login") {
            val req   = call.receive<LoginRequest>()
            val token = authService.login(req.email.trim().lowercase(), req.password)
            val user  = userRepository.findByEmail(req.email)!!

            logRepository.log(
                userId    = user.userId,
                action    = "USER_LOGIN",
                ipAddress = call.request.origin.remoteHost,
                userAgent = call.request.headers["User-Agent"],
                meta      = """{"success":true}"""
            )

            call.respond(AuthResponse(
                token    = token,
                userId   = user.userId.toString(),
                fullName = user.fullName,
                role     = user.roleName
            ))
        }

        authenticate("jwt-auth") {

            // GET /api/v1/auth/me
            get("/me") {
                val userId = UUID.fromString(call.getUserId())
                val user   = userRepository.findById(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(UserProfileDto(
                    userId         = user.userId.toString(),
                    fullName       = user.fullName,
                    email          = user.email,
                    phone          = user.phone,
                    role           = user.roleName,
                    isActive       = user.isActive,
                    consentMedical = user.consentMedical,
                    consentAi      = user.consentAi
                ))
            }

            // PATCH /api/v1/auth/me
            patch("/me") {
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<UpdateProfileRequest>()

                if (req.fullName != null) {
                    require(req.fullName.trim().length >= 2) { "Имя должно содержать минимум 2 символа" }
                }

                val updated = userRepository.updateProfile(
                    userId   = userId,
                    fullName = req.fullName,
                    phone    = req.phone
                )

                if (!updated) return@patch call.respond(HttpStatusCode.NotFound)

                val user = userRepository.findById(userId)!!
                call.respond(UserProfileDto(
                    userId         = user.userId.toString(),
                    fullName       = user.fullName,
                    email          = user.email,
                    phone          = user.phone,
                    role           = user.roleName,
                    isActive       = user.isActive,
                    consentMedical = user.consentMedical,
                    consentAi      = user.consentAi
                ))
            }

            // POST /api/v1/auth/fcm-token — сохранить FCM-токен устройства
            post("/fcm-token") {
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<RegisterFcmTokenRequest>()

                if (req.token.isBlank())
                    return@post call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Токен не может быть пустым"))

                fcmTokenRepository.saveToken(
                    userId   = userId,
                    token    = req.token,
                    deviceId = req.deviceId
                )

                call.respond(mapOf("message" to "Токен сохранён"))
            }

            // DELETE /api/v1/auth/fcm-token — разлогин, деактивировать токен
            delete("/fcm-token") {
                val req = call.receive<RegisterFcmTokenRequest>()
                fcmTokenRepository.deactivateToken(req.token)
                call.respond(mapOf("message" to "Токен деактивирован"))
            }
        }
    }
}