package com.eva.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.hours

fun Application.configureHTTP() {

    // CORS не нужен: API используется только мобильным клиентом (Android).
    // Если потребуется веб-панель — добавить install(CORS) { allowHost("admin.example.com", ...) }

    install(RateLimit) {
        // 10 запросов к AI-анализу в час на пользователя
        register(RateLimitName("ai_analyze")) {
            rateLimiter(limit = 10, refillPeriod = 1.hours)
            requestKey { call ->
                call.principal<JWTPrincipal>()?.payload?.subject ?: "anonymous"
            }
        }
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
        format { call ->
            val status = call.response.status()
            val method = call.request.httpMethod.value
            val path   = call.request.path()
            "$method $path → $status"
        }
    }

    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "Ресурс не найден"))
        }

        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", "Требуется авторизация"))
        }

        status(HttpStatusCode.Forbidden) { call, _ ->
            call.respond(HttpStatusCode.Forbidden, ApiError("FORBIDDEN", "Недостаточно прав"))
        }

        exception<ConflictException> { call, e ->
            call.respond(HttpStatusCode.Conflict, ApiError("CONFLICT", e.message ?: "Конфликт данных"))
        }

        exception<IllegalArgumentException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", e.message ?: "Некорректный запрос"))
        }

        exception<Throwable> { call, e ->
            call.application.log.error("Unhandled exception", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiError("INTERNAL_ERROR", "Внутренняя ошибка сервера")
            )
        }
    }
}

@kotlinx.serialization.Serializable
data class ApiError(
    val code: String,
    val message: String
)

class ConflictException(message: String) : Exception(message)