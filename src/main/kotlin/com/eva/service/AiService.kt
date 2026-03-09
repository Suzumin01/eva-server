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

class AiService(config: ApplicationConfig) {

    private val log       = LoggerFactory.getLogger(AiService::class.java)
    private val aiConfig  = config.config("ai")
    private val baseUrl   = aiConfig.property("baseUrl").getString()
    private val timeoutMs = aiConfig.property("timeoutMs").getString().toLong()

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(Logging) { level = LogLevel.INFO }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 3000
        }
    }

    suspend fun analyze(symptomsText: String): AiAnalysisResult {
        return try {
            analyzeStub(symptomsText)
        } catch (e: Exception) {
            log.error("AI service error: ${e.message}", e)
            AiAnalysisResult(
                diagnosis          = "AI-сервис временно недоступен.",
                recommendations    = "Пожалуйста, запишитесь к врачу для получения консультации.",
                urgency            = "normal",
                confidence         = BigDecimal("0.0"),
                modelVersion       = "stub-fallback",
                processingMs       = 0,
                rawResponse        = null,
                isStub             = true,
                specializationName = null
            )
        }
    }

    private fun analyzeStub(symptomsText: String): AiAnalysisResult {
        log.info("[AI STUB] Analyzing: ${symptomsText.take(50)}...")
        val start     = System.currentTimeMillis()
        val lowerText = symptomsText.lowercase()

        data class R(val diagnosis: String, val recommendations: String,
                     val urgency: String, val confidence: String, val spec: String)

        val r = when {
            "боль в груди" in lowerText || "сердце" in lowerText ->
                R("Возможные проблемы с сердечно-сосудистой системой",
                    "Рекомендуется СРОЧНОЕ обращение к кардиологу. Вызовите скорую помощь при усилении боли.",
                    "urgent", "0.7200", "Кардиология")
            "температур" in lowerText || "простуд" in lowerText || "насморк" in lowerText ->
                R("Вероятное направление: ОРВИ или грипп",
                    "Рекомендуется обратиться к терапевту. Обильное питьё, постельный режим.",
                    "normal", "0.7800", "Терапия")
            "голов" in lowerText && "боль" in lowerText ->
                R("Головная боль напряжения или мигрень",
                    "Рекомендуется консультация невролога. Избегайте стресса, обеспечьте отдых.",
                    "normal", "0.6500", "Неврология")
            "живот" in lowerText || "желудок" in lowerText ->
                R("Возможные нарушения ЖКТ",
                    "Рекомендуется обратиться к терапевту или гастроэнтерологу.",
                    "normal", "0.6000", "Гастроэнтерология")
            "сыпь" in lowerText || "зуд" in lowerText || "кожа" in lowerText ->
                R("Возможное кожное заболевание или аллергическая реакция",
                    "Рекомендуется консультация дерматолога.",
                    "low", "0.5500", "Дерматология")
            else ->
                R("Недостаточно данных для предварительного анализа",
                    "Рекомендуется очная консультация терапевта для уточнения диагноза.",
                    "low", "0.3000", "Терапия")
        }

        return AiAnalysisResult(
            diagnosis          = "${r.diagnosis}\n\n⚠️ ВАЖНО: данный анализ является предварительным и не заменяет консультацию врача.",
            recommendations    = r.recommendations,
            urgency            = r.urgency,
            confidence         = BigDecimal(r.confidence),
            modelVersion       = "eva-stub-v1.0",
            processingMs       = (System.currentTimeMillis() - start).toInt() + (100..400).random(),
            rawResponse        = """{"stub":true,"text":"${symptomsText.take(50)}"}""",
            isStub             = true,
            specializationName = r.spec
        )
    }

    @Suppress("unused")
    private suspend fun analyzeReal(symptomsText: String): AiAnalysisResult {
        val start = System.currentTimeMillis()
        val response: AiApiResponse = httpClient.post("$baseUrl/analyze") {
            contentType(ContentType.Application.Json)
            setBody(AiApiRequest(symptomsText = symptomsText))
        }.body()
        return AiAnalysisResult(
            diagnosis          = response.diagnosis,
            recommendations    = response.recommendations,
            urgency            = response.urgency,
            confidence         = BigDecimal(response.confidence.toString()),
            modelVersion       = response.modelVersion,
            processingMs       = (System.currentTimeMillis() - start).toInt(),
            rawResponse        = response.toString(),
            isStub             = false,
            specializationName = null
        )
    }
}

data class AiAnalysisResult(
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val confidence: BigDecimal,
    val modelVersion: String,
    val processingMs: Int?,
    val rawResponse: String?,
    val isStub: Boolean = false,
    val specializationName: String? = null
)

@Serializable data class AiApiRequest(val symptomsText: String)

@Serializable
data class AiApiResponse(
    val diagnosis: String,
    val recommendations: String,
    val urgency: String,
    val confidence: Double,
    val modelVersion: String
)