package com.eva.service

import io.ktor.server.config.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiServiceTest {

    // Минимальная заглушка ApplicationConfig для тестов
    private fun configWithKey(apiKey: String?) = MapApplicationConfig(
        "openai.apiKey" to (apiKey ?: ""),
        "openai.model"  to "gpt-4o-mini"
    ).let { map ->
        // Если apiKey пустой — имитируем отсутствие ключа через null-возврат в propertyOrNull
        if (apiKey == null) {
            object : ApplicationConfig {
                override fun property(path: String): ApplicationConfigValue =
                    map.property(path)
                override fun propertyOrNull(path: String): ApplicationConfigValue? =
                    if (path == "openai.apiKey") null else map.propertyOrNull(path)
                override fun keys(): Set<String> = map.keys()
                override fun config(path: String): ApplicationConfig = map.config(path)
                override fun configList(path: String): List<ApplicationConfig> = map.configList(path)
                override fun toMap(): Map<String, Any?> = map.toMap()
            }
        } else {
            map
        }
    }

    @Test
    fun `analyze without API key returns stub result with isStub true`() {
        val service = AiService(configWithKey(null))

        // analyze — suspend, запускаем через runBlocking
        val result = kotlinx.coroutines.runBlocking {
            service.analyze("Болит голова и температура 38")
        }
        service.close()

        assertTrue(result.isStub, "Должна вернуться заглушка когда apiKey = null")
        assertEquals("fallback", result.modelVersion)
        assertEquals(BigDecimal("0.0000"), result.confidence)
    }

    @Test
    fun `fallback result always recommends general practitioner`() {
        val service = AiService(configWithKey(null))

        val result = kotlinx.coroutines.runBlocking {
            service.analyze("Любые симптомы")
        }
        service.close()

        assertEquals("Терапевт", result.specializationName)
        assertTrue(result.recommendations.contains("терапевт", ignoreCase = true))
    }

    @Test
    fun `fallback result has null processingMs`() {
        val service = AiService(configWithKey(null))

        val result = kotlinx.coroutines.runBlocking {
            service.analyze("Симптомы пациента")
        }
        service.close()

        assertNull(result.processingMs,
            "processingMs должен быть null для заглушки (БД CHECK: IS NULL OR > 0)")
    }

    @Test
    fun `fallback result has normal urgency`() {
        val service = AiService(configWithKey(null))

        val result = kotlinx.coroutines.runBlocking {
            service.analyze("Симптомы")
        }
        service.close()

        assertEquals("normal", result.urgency)
        assertNotNull(result.diagnosis)
    }
}
