package com.eva.api.routes

import com.eva.api.dto.*
import com.eva.data.repository.LogRepositoryImpl
import com.eva.data.repository.UserRepositoryImpl
import com.eva.plugins.getUserId
import com.eva.service.AuthService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun Route.authRoutes(
    authService: AuthService,
    userRepository: UserRepositoryImpl,
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
            val req = call.receive<LoginRequest>()
            val token = authService.login(req.email.trim().lowercase(), req.password)

            val user = userRepository.findByEmail(req.email)!!

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

        // GET /api/v1/auth/me  — текущий пользователь
        authenticate("jwt-auth") {
            get("/me") {
                val userId = UUID.fromString(call.getUserId())
                val user   = userRepository.findById(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                call.respond(UserProfileResponse(
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
        }
    }
}
