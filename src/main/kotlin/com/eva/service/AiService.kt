package com.eva.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.config.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import kotlin.system.measureTimeMillis

class AiService(config: ApplicationConfig) : java.io.Closeable {

    private val logger = LoggerFactory.getLogger(AiService::class.java)

    private val apiKey   = config.propertyOrNull("yandex.apiKey")?.getString()
    private val folderId = config.propertyOrNull("yandex.folderId")?.getString() ?: ""
    private val promptId = config.propertyOrNull("yandex.promptId")?.getString() ?: ""

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    override fun close() { httpClient.close() }

    suspend fun analyze(
        symptomsText: String,
        availableSpecializations: List<String> = emptyList(),
        allergies: String? = null,
        chronicDiseases: String? = null,
        dateOfBirth: String? = null
    ): AiAnalysisResult {
        if (apiKey == null) {
            logger.warn("YANDEX_API_KEY не задан — возвращаю заглушку")
            return fallbackResult()
        }
        logger.info("Yandex AI запрос: ${symptomsText.take(60)}...")

        val rawJson: String
        val processingMs: Long

        try {
            val response: YandexResponse
            processingMs = measureTimeMillis {
                response = httpClient.post("https://ai.api.cloud.yandex.net/v1/responses") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    header("OpenAI-Organization", folderId)
                    setBody(
                        YandexRequest(
                            prompt = YandexPrompt(id = promptId),
                            input  = buildUserMessage(symptomsText, availableSpecializations, allergies, chronicDiseases, dateOfBirth)
                        )
                    )
                }.body()
            }

            val usage = response.usage
            if (usage != null) {
                logger.info(
                    "Yandex AI ответил за ${processingMs}мс | " +
                    "токены: input=${usage.input_tokens} output=${usage.output_tokens} total=${usage.total_tokens}"
                )
            }

            rawJson = response.output
                .firstOrNull { it.type == "message" }
                ?.content
                ?.firstOrNull { it.type == "output_text" }
                ?.text
                ?: throw IllegalStateException("Yandex AI вернул пустой output")

        } catch (e: Exception) {
            logger.error("Ошибка обращения к Yandex AI: ${e.message}")
            return fallbackResult()
        }

        return try {
            parseAiResponse(rawJson, processingMs)
        } catch (e: Exception) {
            logger.error("Не удалось распарсить JSON от Yandex AI: $rawJson", e)
            fallbackResult(rawJson)
        }
    }

    private fun buildUserMessage(
        symptomsText: String,
        availableSpecializations: List<String>,
        allergies: String?,
        chronicDiseases: String?,
        dateOfBirth: String?
    ): String {
        val sb = StringBuilder()
        if (availableSpecializations.isNotEmpty()) {
            sb.appendLine("Доступные специализации врачей (используй ТОЛЬКО одну из них в specializationName):")
            sb.appendLine(availableSpecializations.joinToString(", "))
            sb.appendLine()
        }
        val hasHealthData = !allergies.isNullOrBlank() || !chronicDiseases.isNullOrBlank() || !dateOfBirth.isNullOrBlank()
        if (hasHealthData) {
            sb.appendLine("Данные профиля пациента:")
            dateOfBirth?.takeIf { it.isNotBlank() }?.let { sb.appendLine("  Дата рождения: $it") }
            allergies?.takeIf { it.isNotBlank() }?.let { sb.appendLine("  Аллергии: $it") }
            chronicDiseases?.takeIf { it.isNotBlank() }?.let { sb.appendLine("  Хронические заболевания: $it") }
            sb.appendLine()
        }
        sb.appendLine("Симптомы пациента:")
        sb.appendLine("<symptoms>")
        sb.appendLine(symptomsText)
        sb.append("</symptoms>")
        return sb.toString()
    }

    private fun parseAiResponse(rawJson: String, processingMs: Long): AiAnalysisResult {
        val parsed = json.decodeFromString<AiJsonResponse>(rawJson)
        val safeUrgency = parsed.urgency
            .lowercase()
            .takeIf { it in setOf("low", "normal", "urgent", "emergency") }
            ?: "normal"
        return AiAnalysisResult(
            title              = parsed.title.trim().ifBlank { "Анализ симптомов" },
            diagnosis          = parsed.diagnosis.trim(),
            recommendations    = parsed.recommendations.trim(),
            urgency            = safeUrgency,
            specializationName = parsed.specializationName.trim().ifBlank { null }
                ?.replaceFirstChar { it.uppercase() },
            confidence         = BigDecimal(parsed.confidence.coerceIn(0.0, 1.0))
                .setScale(4, java.math.RoundingMode.HALF_UP),
            modelVersion       = "alice-ai-llm",
            processingMs       = processingMs.toInt(),
            rawResponse        = rawJson,
            isStub             = false
        )
    }

    private fun fallbackResult(raw: String? = null) = AiAnalysisResult(
        title              = "Анализ симптомов",
        diagnosis          = "Не удалось выполнить анализ симптомов. Пожалуйста, попробуйте позже или обратитесь к врачу напрямую.",
        recommendations    = "Запишитесь на приём к терапевту для первичного осмотра.",
        urgency            = "normal",
        specializationName = "Терапевт",
        confidence         = BigDecimal("0.0000"),
        modelVersion       = "fallback",
        processingMs       = null,
        rawResponse        = raw,
        isStub             = true
    )
}

@Serializable
private data class YandexRequest(
    val prompt: YandexPrompt,
    val input: String
)

@Serializable
private data class YandexPrompt(val id: String)

@Serializable
private data class YandexResponse(
    val output: List<YandexOutputItem>,
    val usage: YandexUsage? = null
)

@Serializable
private data class YandexOutputItem(
    val type: String,
    val content: List<YandexContent>? = null
)

@Serializable
private data class YandexContent(
    val type: String,
    val text: String? = null
)

@Serializable
private data class YandexUsage(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val total_tokens: Int = 0
)

@Serializable
private data class AiJsonResponse(
    val title              : String = "",
    val diagnosis          : String,
    val recommendations    : String,
    val urgency            : String,
    val specializationName : String,
    val confidence         : Double
)

data class AiAnalysisResult(
    val title              : String,
    val diagnosis          : String,
    val recommendations    : String,
    val urgency            : String,
    val specializationName : String?,
    val confidence         : BigDecimal,
    val modelVersion       : String,
    val processingMs       : Int?,
    val rawResponse        : String?,
    val isStub             : Boolean = false
)
