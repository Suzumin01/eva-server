package com.eva.plugins

import com.eva.api.routes.*
import com.eva.data.repository.*
import com.eva.service.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

private val routingLogger = LoggerFactory.getLogger("com.eva.plugins.Routing")

fun Application.configureRouting() {
    val appTimezone        = environment.config.tryGetString("app.timezone") ?: "Europe/Moscow"
    val fcmCredentialsPath = environment.config.tryGetString("fcm.credentialsPath")

    val userRepository           = UserRepositoryImpl()
    val doctorRepository         = DoctorRepositoryImpl()
    val specializationRepository = SpecializationRepositoryImpl()
    val clinicRepository         = ClinicRepositoryImpl()
    val scheduleRepository       = ScheduleRepositoryImpl(appTimezone)
    val appointmentRepository    = AppointmentRepositoryImpl()
    val symptomsRepository       = SymptomsRepositoryImpl()
    val notificationRepository   = NotificationRepositoryImpl()
    val fcmTokenRepository        = FcmTokenRepositoryImpl()
    val logRepository             = LogRepositoryImpl()
    val documentRepository        = DocumentRepositoryImpl()
    val refreshTokenRepository    = RefreshTokenRepositoryImpl()

    val jwtConfig = environment.config.config("jwt")
    val authService = AuthService(
        userRepository         = userRepository,
        refreshTokenRepository = refreshTokenRepository,
        secret                 = jwtConfig.property("secret").getString(),
        issuer                 = jwtConfig.property("issuer").getString(),
        audience               = jwtConfig.property("audience").getString(),
        expirationMs           = jwtConfig.property("expirationMs").getString().toLong()
    )

    val fcmService = FcmService(fcmTokenRepository, fcmCredentialsPath)
    val aiService  = AiService(environment.config)
    environment.monitor.subscribe(ApplicationStopped) { aiService.close() }

    val notificationService = NotificationService(
        notificationRepository = notificationRepository,
        fcmService             = fcmService,
        fcmTokenRepository     = fcmTokenRepository,
        appointmentRepository  = appointmentRepository
    )

    // Ежедневные напоминания о записях — запускаются каждый день в 08:00 по appTimezone
    launch(Dispatchers.IO) {
        val zoneId = ZoneId.of(appTimezone)
        while (true) {
            try {
                val now  = ZonedDateTime.now(zoneId)
                val next = now.toLocalDate().atTime(LocalTime.of(8, 0)).atZone(zoneId)
                    .let { if (it.isAfter(now)) it else it.plusDays(1) }
                val delayMs = java.time.Duration.between(now, next).toMillis()
                delay(delayMs)
                routingLogger.info("Отправка ежедневных напоминаний о записях")
                notificationService.scheduleDailyReminders()
            } catch (e: Exception) {
                routingLogger.error("Ошибка в планировщике напоминаний: ${e.message}", e)
                delay(60_000)
            }
        }
    }

    routing {
        route("/api/v1") {
            authRoutes(authService, userRepository, fcmTokenRepository, logRepository, refreshTokenRepository)
            specializationRoutes(specializationRepository)
            doctorRoutes(doctorRepository, clinicRepository)
            scheduleRoutes(scheduleRepository)
            appointmentRoutes(appointmentRepository, notificationService, logRepository, appTimezone)
            symptomsRoutes(symptomsRepository, aiService)
            notificationRoutes(notificationRepository, fcmTokenRepository)
            documentRoutes(documentRepository)
            healthRoute()
        }
    }
}