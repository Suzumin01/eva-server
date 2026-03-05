package com.eva.service

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory

class FcmService {

    private val logger = LoggerFactory.getLogger(FcmService::class.java)
    private val enabled: Boolean

    init {
        val serviceAccountPath = this::class.java.classLoader
            .getResourceAsStream("firebase-service-account.json")

        if (serviceAccountPath != null) {
            try {
                val options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccountPath))
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
        } catch (e: Exception) {
            logger.error("Ошибка отправки FCM на токен $fcmToken: ${e.message}")
            false
        }
    }

    fun sendToTokens(
        fcmTokens: List<String>,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ) {
        fcmTokens.forEach { token -> sendToToken(token, title, body, data) }
    }
}