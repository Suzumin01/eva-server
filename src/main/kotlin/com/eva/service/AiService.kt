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

// Системный промпт — вся «медицинская логика» живёт здесь
private val SYSTEM_PROMPT = """
Ты — медицинский ассистент мобильного приложения EVA. Твоя задача — проанализировать
симптомы пользователя и вернуть структурированный JSON-ответ.

СТРОГИЕ ПРАВИЛА:
1. Отвечай ТОЛЬКО валидным JSON. Никаких markdown-блоков, пояснений или текста вне JSON.
2. Никогда не ставь окончательный диагноз — только предварительную оценку.
3. При угрозе жизни (боль в груди, затруднённое дыхание, потеря сознания,
   признаки инсульта — асимметрия лица, онемение конечностей, спутанность речи)
   устанавливай urgency = "emergency" и рекомендуй немедленно вызвать скорую помощь (103/112).
   При серьёзных, но не экстренных симптомах устанавливай urgency = "urgent".
4. Если описание слишком короткое или неинформативное — попроси уточнить симптомы
   через поле diagnosis, confidence поставь ниже 0.4.
5. Отвечай исключительно на русском языке.
6. specializationName — название специализации врача СТРОГО из списка доступных специализаций,
   который будет указан в сообщении пользователя. Используй точное написание из списка.
7. title — краткий заголовок анализа (3-6 слов), описывающий основную жалобу или предварительный
   вывод. Например: "Возможная ОРВИ с температурой" или "Боли в пояснице". Без точки в конце.

Структура ответа (строго эта, без лишних полей):
{
  "title": "Краткий заголовок 3-6 слов",
  "diagnosis": "Предварительная оценка состояния в 1-3 предложениях",
  "recommendations": "Рекомендации через символ \n, например:\n1. Обратитесь к врачу\n2. Соблюдайте постельный режим",
  "urgency": "low",
  "specializationName": "Терапевт",
  "confidence": 0.75
}

Допустимые значения urgency:
- "low"       — несрочно, плановая запись
- "normal"    — стоит обратиться в течение 1-3 дней
- "urgent"    — срочно, обратитесь к врачу сегодня
- "emergency" — экстренно, немедленно вызовите скорую помощь
""".trimIndent()

// AiService
class AiService(config: ApplicationConfig) : java.io.Closeable {

    private val logger = LoggerFactory.getLogger(AiService::class.java)

    private val apiKey      = config.propertyOrNull("openai.apiKey")?.getString()
    private val model       = config.propertyOrNull("openai.model")?.getString() ?: "gpt-4o-mini"
    private val temperature = config.propertyOrNull("openai.temperature")?.getString()?.toDoubleOrNull() ?: 0.3
    private val maxTokens   = config.propertyOrNull("openai.maxTokens")?.getString()?.toIntOrNull() ?: 600

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    override fun close() { httpClient.close() }

    suspend fun analyze(symptomsText: String, availableSpecializations: List<String> = emptyList()): AiAnalysisResult {
        if (apiKey == null) {
            logger.warn("OPENAI_API_KEY не задан — возвращаю заглушку")
            return fallbackResult()
        }
        logger.info("OpenAI запрос: ${symptomsText.take(60)}...")

        val rawJson: String
        val processingMs: Long

        try {
            val response: OpenAiResponse
            processingMs = measureTimeMillis {
                response = httpClient.post("https://api.openai.com/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    header("Authorization", "Bearer $apiKey")
                    setBody(buildRequestBody(symptomsText, availableSpecializations))
                }.body()
            }

            val usage = response.usage
            logger.info(
                "OpenAI ответил за ${processingMs}мс | " +
                        "токены: prompt=${usage.prompt_tokens} completion=${usage.completion_tokens} total=${usage.total_tokens}"
            )

            rawJson = response.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("OpenAI вернул пустой список choices")

        } catch (e: Exception) {
            logger.error("Ошибка обращения к OpenAI: ${e.message}")
            return fallbackResult()
        }

        return try {
            parseAiResponse(rawJson, processingMs)
        } catch (e: Exception) {
            logger.error("Не удалось распарсить JSON от OpenAI: $rawJson", e)
            fallbackResult(rawJson)
        }
    }

    private fun buildRequestBody(symptomsText: String, availableSpecializations: List<String>) = OpenAiRequest(
        model    = model,
        messages = listOf(
            OpenAiMessage(role = "system", content = SYSTEM_PROMPT),
            OpenAiMessage(role = "user", content = buildUserMessage(symptomsText, availableSpecializations))
        ),
        temperature     = temperature,
        max_tokens      = maxTokens,
        response_format = ResponseFormat(type = "json_object")
    )

    private fun buildUserMessage(symptomsText: String, availableSpecializations: List<String>): String {
        val sb = StringBuilder()
        if (availableSpecializations.isNotEmpty()) {
            sb.appendLine("Доступные специализации врачей в системе (используй ТОЛЬКО одну из них в specializationName):")
            sb.appendLine(availableSpecializations.joinToString(", "))
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
            specializationName = parsed.specializationName.trim().ifBlank { null },
            confidence         = BigDecimal(parsed.confidence.coerceIn(0.0, 1.0))
                .setScale(4, java.math.RoundingMode.HALF_UP),
            modelVersion       = model,
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
private data class OpenAiRequest(
    val model           : String,
    val messages        : List<OpenAiMessage>,
    val temperature     : Double,
    val max_tokens      : Int,
    val response_format : ResponseFormat
)

@Serializable
private data class OpenAiMessage(val role: String, val content: String)

@Serializable
private data class ResponseFormat(val type: String)

@Serializable
private data class OpenAiResponse(
    val choices : List<OpenAiChoice>,
    val usage   : OpenAiUsage
)

@Serializable
private data class OpenAiChoice(val message: OpenAiMessage)

@Serializable
private data class OpenAiUsage(
    val prompt_tokens     : Int,
    val completion_tokens : Int,
    val total_tokens      : Int
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