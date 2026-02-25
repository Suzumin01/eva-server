package com.eva.plugins

import com.eva.api.routes.*
import com.eva.data.repository.*
import com.eva.service.*
import io.ktor.server.application.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    val userRepository         = UserRepositoryImpl()
    val doctorRepository       = DoctorRepositoryImpl()
    val clinicRepository       = ClinicRepositoryImpl()
    val scheduleRepository     = ScheduleRepositoryImpl()
    val appointmentRepository  = AppointmentRepositoryImpl()
    val symptomsRepository     = SymptomsRepositoryImpl()
    val notificationRepository = NotificationRepositoryImpl()
    val logRepository          = LogRepositoryImpl()

    val jwtConfig = environment.config.config("jwt")
    val authService = AuthService(
        userRepository = userRepository,
        secret         = jwtConfig.property("secret").getString(),
        issuer         = jwtConfig.property("issuer").getString(),
        audience       = jwtConfig.property("audience").getString(),
        expirationMs   = jwtConfig.property("expirationMs").getString().toLong()
    )

    val fcmService = FcmService(environment.config)
    val aiService  = AiService(environment.config)

    val notificationService = NotificationService(
        notificationRepository = notificationRepository,
        fcmService             = fcmService,
        userRepository         = userRepository
    )

    routing {
        route("/api/v1") {
            authRoutes(authService, userRepository, logRepository)
            doctorRoutes(doctorRepository, clinicRepository)
            scheduleRoutes(scheduleRepository)
            appointmentRoutes(appointmentRepository, notificationService, logRepository)
            symptomsRoutes(symptomsRepository, aiService)
            notificationRoutes(notificationRepository)
            healthRoute()
        }
    }
}
