package com.eva.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal

/**
 * AiService — интеграция с Python FastAPI AI-модулем.
 *
 * Текущий режим: STUB (заглушка).
 * Для подключения реального AI-модуля раскомментировать метод analyzeReal()
 * и заменить вызов в analyze().
 */
class AiService(config: ApplicationConfig) {

    private val log = LoggerFactory.getLogger(AiService::class.java)
    private val aiConfig    = config.config("ai")
    private val baseUrl     = aiConfig.property("baseUrl").getString()
    private val timeoutMs   = aiConfig.property("timeoutMs").getString().toLong()

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(Logging) {
            level = LogLevel.INFO
        }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 3000
        }
    }

    // Основной метод — сейчас возвращает заглушку
    suspend fun analyze(symptomsText: String): AiAnalysisResult {
        return try {
            // Для реальной интеграции заменить на: analyzeReal(symptomsText)
            analyzeStub(symptomsText)
        } catch (e: Exception) {
            log.error("AI service error: ${e.message}", e)
            // Fallback — всегда возвращаем безопасный ответ
            AiAnalysisResult(
                diagnosis       = "AI-сервис временно недоступен.",
                recommendations = "Пожалуйста, запишитесь к врачу для получения консультации.",
                urgency         = "normal",
                confidence      = BigDecimal("0.0"),
                modelVersion    = "stub-fallback",
                processingMs    = 0,
                rawResponse     = null,
                isStub          = true
            )
        }
    }

    // ЗАГЛУШКА (stub) — детерминированные ответы
    private fun analyzeStub(symptomsText: String): AiAnalysisResult {
        log.info("[AI STUB] Analyzing: ${symptomsText.take(50)}...")
        val start = System.currentTimeMillis()

        // Простая эвристика для демонстрации
        val lowerText = symptomsText.lowercase()
        val (diagnosis, recommendations, urgency, confidence) = when {
            "боль в груди" in lowerText || "сердце" in lowerText ->
                Quadruple(
                    "Возможные проблемы с сердечно-сосудистой системой",
                    "Рекомендуется СРОЧНОЕ обращение к кардиологу. Вызовите скорую помощь при усилении боли.",
                    "urgent",
                    "0.7200"
                )
            "температур" in lowerText || "простуд" in lowerText || "насморк" in lowerText ->
                Quadruple(
                    "Вероятное направление: ОРВИ или грипп",
                    "Рекомендуется обратиться к терапевту. Обильное питьё, постельный режим.",
                    "normal",
                    "0.7800"
                )
            "голов" in lowerText && "боль" in lowerText ->
                Quadruple(
                    "Головная боль напряжения или мигрень",
                    "Рекомендуется консультация невролога. Избегайте стресса, обеспечьте отдых.",
                    "normal",
                    "0.6500"
                )
            "живот" in lowerText || "желудок" in lowerText ->
                Quadruple(
                    "Возможные нарушения ЖКТ",
                    "Рекомендуется обратиться к терапевту или гастроэнтерологу.",
                    "normal",
                    "0.6000"
                )
            "сыпь" in lowerText || "зуд" in lowerText || "кожа" in lowerText ->
                Quadruple(
                    "Возможное кожное заболевание или аллергическая реакция",
                    "Рекомендуется консультация дерматолога.",
                    "low",
                    "0.5500"
                )
            else ->
                Quadruple(
                    "Недостаточно данных для предварительного анализа",
                    "Рекомендуется очная консультация терапевта для уточнения диагноза.",
                    "low",
                    "0.3000"
                )
        }

        val processingMs = (System.currentTimeMillis() - start).toInt() + (100..400).random()

        return AiAnalysisResult(
            diagnosis       = "$diagnosis\n\n⚠️ ВАЖНО: данный анализ является предварительным и не заменяет консультацию врача.",
            recommendations = recommendations,
            urgency         = urgency,
            confidence      = BigDecimal(confidence),
            modelVersion    = "eva-stub-v1.0",
            processingMs    = processingMs,
            rawResponse     = """{"stub":true,"text":"${symptomsText.take(50)}"}""",
            isStub          = true
        )
    }

    // РЕАЛЬНАЯ интеграция с FastAPI AI-модулем
    // Раскомментировать когда AI-сервис готов
    @Suppress("unused")
    private suspend fun analyzeReal(symptomsText: String): AiAnalysisResult {
        val start = System.currentTimeMillis()

        val response: AiApiResponse = httpClient.post("$baseUrl/analyze") {
            contentType(ContentType.Application.Json)
            setBody(AiApiRequest(symptomsText = symptomsText))
        }.body()

        return AiAnalysisResult(
            diagnosis       = response.diagnosis,
            recommendations = response.recommendations,
            urgency         = response.urgency,
            confidence      = BigDecimal(response.confidence.toString()),
            modelVersion    = response.modelVersion,
            processingMs    = (System.currentTimeMillis() - start).toInt(),
            rawResponse     = response.toString(),
            isStub          = false
        )
    }
}

// DTOs для AI-модуля

data class AiAnalysisResult(
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val confidence: BigDecimal,
    val modelVersion: String,
    val processingMs: Int?,
    val rawResponse: String?,
    val isStub: Boolean = false
)

@Serializable
data class AiApiRequest(
    val symptomsText: String
)

@Serializable
data class AiApiResponse(
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val confidence: Double,
    val modelVersion: String
)

// Вспомогательный класс для деструктуризации четвёрки
private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component1() = first
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component2() = second
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component3() = third
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component4() = fourth
