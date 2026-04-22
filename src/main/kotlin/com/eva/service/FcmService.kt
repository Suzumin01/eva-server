package com.eva.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.slf4j.LoggerFactory
import java.io.File

class FcmService(
    private val fcmTokenRepository: com.eva.data.repository.FcmTokenRepositoryImpl,
    credentialsPath: String? = null
) {

    private val logger = LoggerFactory.getLogger(FcmService::class.java)
    private val enabled: Boolean

    init {
        // Приоритет: явный путь из конфига → classpath (fallback для локальной разработки)
        val stream = credentialsPath
            ?.takeIf { it.isNotBlank() }
            ?.let { path ->
                val file = File(path)
                if (file.exists()) file.inputStream()
                else {
                    logger.warn("FCM credentials file not found at '$path', falling back to classpath")
                    null
                }
            }
            ?: this::class.java.classLoader.getResourceAsStream("firebase-service-account.json")

        if (stream != null) {
            try {
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(stream))
                    .build()

                if (FirebaseApp.getApps().isEmpty()) {
                    FirebaseApp.initializeApp(options)
                }

                enabled = true
                logger.info("Firebase Admin SDK инициализирован")
            } catch (e: Exception) {
                logger.error("Ошибка инициализации Firebase: ${e.message}")
                throw e
            }
        } else {
            enabled = false
            logger.warn("firebase-service-account.json не найден — push-уведомления отключены")
        }
    }

    fun sendToToken(
        fcmToken: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean {
        if (!enabled) {
            logger.debug("FCM отключён, уведомление не отправлено: $title")
            return false
        }
        return try {
            val message = Message.builder()
                .setToken(fcmToken)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putAllData(data)
                .build()

            val response = FirebaseMessaging.getInstance().send(message)
            logger.info("FCM отправлено: $response")
            true
        } catch (e: com.google.firebase.messaging.FirebaseMessagingException) {
            if (e.messagingErrorCode == com.google.firebase.messaging.MessagingErrorCode.UNREGISTERED ||
                e.messagingErrorCode == com.google.firebase.messaging.MessagingErrorCode.INVALID_ARGUMENT) {
                logger.warn("Деактивирую устаревший FCM-токен: ${fcmToken.take(20)}...")
                fcmTokenRepository.deactivateTokenInternal(fcmToken)
            } else {
                logger.error("Ошибка отправки FCM на токен ${fcmToken.take(20)}...: ${e.message}")
            }
            false
        } catch (e: Exception) {
            logger.error("Ошибка отправки FCM: ${e.message}")
            false
        }
    }

    suspend fun sendToTokens(
        fcmTokens: List<String>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) = coroutineScope {
        fcmTokens.map { token ->
            async(Dispatchers.IO) { sendToToken(token, title, body, data) }
        }.awaitAll()
    }
}