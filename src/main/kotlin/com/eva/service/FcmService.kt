package com.eva.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.*
import io.ktor.server.config.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.FileInputStream

/**
 * FcmService — отправка push-уведомлений через Firebase Cloud Messaging.
 */
class FcmService(config: ApplicationConfig) {

    private val log = LoggerFactory.getLogger(FcmService::class.java)
    private val credentialsPath = config.config("fcm").property("credentialsPath").getString()
    private var initialized = false

    init {
        initFirebase()
    }

    // Инициализация Firebase Admin SDK
    private fun initFirebase() {
        try {
            val serviceAccount = FileInputStream(credentialsPath)
            val options = FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                .build()

            if (FirebaseApp.getApps().isEmpty()) {
                FirebaseApp.initializeApp(options)
                log.info("Firebase Admin SDK initialized from: $credentialsPath")
            }
            initialized = true
        } catch (e: Exception) {
            log.warn("FCM не инициализирован: ${e.message}. Push-уведомления будут логироваться, но не отправляться.")
            initialized = false
        }
    }

    // отправка через Admin SDK

    /**
     * Отправить уведомление на конкретный FCM-токен устройства.
     * @param token  FCM Registration Token устройства
     * @param title  Заголовок уведомления
     * @param body   Текст уведомления
     * @param data   Дополнительные данные (key-value), обрабатываются в приложении
     */
    suspend fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) {
            log.info("[FCM STUB] TO: $token | TITLE: $title | BODY: $body")
            return@withContext true
        }
        try {
            val message = Message.builder()
                .setToken(token)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putAllData(data)
                .setAndroidConfig(
                    AndroidConfig.builder()
                        .setPriority(AndroidConfig.Priority.HIGH)
                        .build()
                )
                .build()

            val response = FirebaseMessaging.getInstance().send(message)
            log.info("FCM sent: $response")
            true
        } catch (e: FirebaseMessagingException) {
            log.error("FCM error: ${e.message}", e)
            false
        }
    }

    /**
     * Отправить уведомление нескольким устройствам сразу (до 500 токенов).
     */
    suspend fun sendToMultiple(
        tokens: List<String>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Int = withContext(Dispatchers.IO) {
        if (!initialized || tokens.isEmpty()) return@withContext 0
        try {
            val message = MulticastMessage.builder()
                .addAllTokens(tokens)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .putAllData(data)
                .build()

            val response = FirebaseMessaging.getInstance().sendEachForMulticast(message)
            log.info("FCM multicast: ${response.successCount} ok, ${response.failureCount} fail")
            response.successCount
        } catch (e: Exception) {
            log.error("FCM multicast error: ${e.message}", e)
            0
        }
    }

    /**
     * Отправить уведомление по топику
     */
    suspend fun sendToTopic(
        topic: String,
        title: String,
        body: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext false
        try {
            val message = Message.builder()
                .setTopic(topic)
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build()
                )
                .build()
            FirebaseMessaging.getInstance().send(message)
            true
        } catch (e: Exception) {
            log.error("FCM topic error: ${e.message}", e)
            false
        }
    }
}
