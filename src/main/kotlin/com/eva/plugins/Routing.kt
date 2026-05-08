package com.eva.plugins

import com.eva.api.routes.*
import com.eva.data.repository.*
import com.eva.data.repository.AdminStatsRepository
import com.eva.service.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val routingLogger = LoggerFactory.getLogger("com.eva.plugins.Routing")

fun Application.configureRouting() {
    val appTimezone        = environment.config.propertyOrNull("app.timezone")?.getString() ?: "Europe/Moscow"
    val fcmCredentialsPath = environment.config.propertyOrNull("fcm.credentialsPath")?.getString()

    val userRepository           = UserRepositoryImpl()
    val doctorRepository         = DoctorRepositoryImpl()
    val specializationRepository = SpecializationRepositoryImpl()
    val clinicRepository         = ClinicRepositoryImpl()
    val scheduleRepository       = ScheduleRepositoryImpl(appTimezone)
    val appointmentRepository    = AppointmentRepositoryImpl(appTimezone)
    val symptomsRepository       = SymptomsRepositoryImpl()
    val notificationRepository   = NotificationRepositoryImpl()
    val fcmTokenRepository        = FcmTokenRepositoryImpl()
    val logRepository             = LogRepositoryImpl()
    val documentRepository        = DocumentRepositoryImpl()
    val refreshTokenRepository       = RefreshTokenRepositoryImpl()
    val passwordResetRepository      = PasswordResetTokenRepositoryImpl()
    val statsRepository              = AdminStatsRepository()

    val jwtConfig = environment.config.config("jwt")
    val authService = AuthService(
        userRepository         = userRepository,
        refreshTokenRepository = refreshTokenRepository,
        passwordResetRepository = passwordResetRepository,
        secret                 = jwtConfig.property("secret").getString(),
        issuer                 = jwtConfig.property("issuer").getString(),
        audience               = jwtConfig.property("audience").getString(),
        expirationMs           = jwtConfig.property("expirationMs").getString().toLong(),
        doctorRepository       = doctorRepository
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

    // Напоминания о записях — проверка каждые 5 минут
    launch(Dispatchers.IO) {
        delay(15_000L) // дать пулу соединений инициализироваться
        while (true) {
            try {
                notificationService.sendPendingReminders()
            } catch (e: Exception) {
                routingLogger.error("Ошибка в планировщике напоминаний: ${e.message}", e)
            }
            delay(5 * 60_000L)
        }
    }

    routing {
        route("/api/v1") {
            authRoutes(authService, userRepository, fcmTokenRepository, logRepository, refreshTokenRepository)
            specializationRoutes(specializationRepository)
            doctorRoutes(doctorRepository, clinicRepository)
            scheduleRoutes(scheduleRepository)
            appointmentRoutes(appointmentRepository, notificationService, logRepository, appTimezone)
            symptomsRoutes(symptomsRepository, specializationRepository, userRepository, aiService)
            notificationRoutes(notificationRepository, fcmTokenRepository)
            documentRoutes(documentRepository)
            adminRoutes(userRepository, doctorRepository, clinicRepository, appointmentRepository, scheduleRepository, statsRepository, specializationRepository, documentRepository)
            doctorDashboardRoutes(appointmentRepository, scheduleRepository, documentRepository)
            healthRoute()
        }
    }
}