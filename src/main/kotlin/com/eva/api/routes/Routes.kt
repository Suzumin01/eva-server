package com.eva.api.routes

import com.eva.api.dto.*
import com.eva.data.repository.*
import com.eva.plugins.getUserId
import com.eva.service.AiService
import com.eva.service.NotificationService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.upsert
import java.util.UUID

fun Route.doctorRoutes(
    doctorRepository: DoctorRepositoryImpl,
    clinicRepository: ClinicRepositoryImpl
) {
    route("/doctors") {
        get {
            val specializationId = call.request.queryParameters["specializationId"]?.toShortOrNull()
            val clinicId         = call.request.queryParameters["clinicId"]?.toIntOrNull()
            val search           = call.request.queryParameters["search"]
            val limit            = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20
            val offset           = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L

            val doctors = doctorRepository.findAll(specializationId, clinicId, search, limit, offset)
            call.respond(DoctorListResponse(
                doctors = doctors.map { it.toDto() },
                total   = doctors.size
            ))
        }

        get("/{id}") {
            val doctorId = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Некорректный ID врача")
            val doctor = doctorRepository.findById(doctorId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "Врач не найден")
            call.respond(doctor.toDto())
        }

        get("/{id}/reviews") {
            val doctorId = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val reviews = doctorRepository.getReviews(doctorId)
            call.respond(reviews.map {
                ReviewResponse(
                    reviewId     = it.reviewId.toString(),
                    userId       = it.userId.toString(),
                    userFullName = it.userFullName,
                    rating       = it.rating.toInt(),
                    comment      = it.comment,
                    createdAt    = it.createdAt.toString()
                )
            })
        }

        authenticate("jwt-auth") {
            post("/{id}/reviews") {
                val doctorId = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<AddReviewRequest>()

                require(req.rating in 1..5) { "Оценка должна быть от 1 до 5" }

                if (!doctorRepository.hasCompletedAppointment(doctorId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden,
                        mapOf("message" to "Отзыв можно оставить только после завершённого приёма"))
                }
                if (doctorRepository.hasReviewed(doctorId, userId)) {
                    return@post call.respond(HttpStatusCode.Conflict,
                        mapOf("message" to "Вы уже оставляли отзыв этому врачу"))
                }

                doctorRepository.addReview(doctorId, userId, req.rating.toShort(), req.comment)
                doctorRepository.recalculateRating(doctorId)
                call.respond(HttpStatusCode.Created, mapOf("message" to "Отзыв добавлен"))
            }

            patch("/reviews/{reviewId}") {
                val reviewId = call.parameters["reviewId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                } ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<UpdateReviewRequest>()

                require(req.rating in 1..5) { "Оценка должна быть от 1 до 5" }

                val updated = doctorRepository.updateReview(reviewId, userId, req.rating.toShort(), req.comment)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound,
                    mapOf("message" to "Отзыв не найден или недоступен для редактирования"))

                call.respond(mapOf("message" to "Отзыв обновлён"))
            }

            delete("/reviews/{reviewId}") {
                val reviewId = call.parameters["reviewId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                } ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val userId = UUID.fromString(call.getUserId())

                val doctorId = doctorRepository.deleteReview(reviewId, userId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound,
                        mapOf("message" to "Отзыв не найден или недоступен для удаления"))

                doctorRepository.recalculateRating(doctorId)
                call.respond(mapOf("message" to "Отзыв удалён"))
            }

            get("/{id}/can-review") {
                val doctorId = call.parameters["id"]?.toIntOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val userId = UUID.fromString(call.getUserId())
                val canReview = doctorRepository.hasCompletedAppointment(doctorId, userId) &&
                        !doctorRepository.hasReviewed(doctorId, userId)
                call.respond(mapOf("canReview" to canReview))
            }
        }
    }

    route("/clinics") {
        get {
            call.respond(clinicRepository.findAll().map {
                ClinicResponse(
                    clinicId     = it.clinicId,
                    clinicName   = it.clinicName,
                    address      = it.address,
                    phone        = it.phone,
                    latitude     = it.latitude?.toString(),
                    longitude    = it.longitude?.toString(),
                    rating       = it.rating?.toString(),
                    doctorsCount = it.doctorsCount
                )
            })
        }
    }
}

private fun com.eva.domain.models.Doctor.toDto() = DoctorResponse(
    doctorId           = doctorId,
    fullName           = fullName,
    clinicId           = clinicId,
    clinicName         = clinicName,
    clinicAddress      = clinicAddress,
    specializationId   = specializationId.toInt(),
    specializationName = specializationName,
    bio                = bio,
    photoUrl           = photoUrl,
    experienceYears    = experienceYears?.toInt(),
    rating             = rating?.toString(),
    reviewsCount       = reviewsCount
)

fun Route.scheduleRoutes(scheduleRepository: ScheduleRepositoryImpl) {
    route("/schedules") {
        get {
            val doctorId = call.request.queryParameters["doctorId"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, "Укажите doctorId")
            val dateStr   = call.request.queryParameters["date"]
            val dateToStr = call.request.queryParameters["dateTo"]
            val date      = dateStr?.let { java.time.LocalDate.parse(it) }
            val dateTo    = dateToStr?.let { java.time.LocalDate.parse(it) }

            val slots = scheduleRepository.findByDoctor(doctorId, date, dateTo)
            call.respond(slots.map {
                ScheduleResponse(
                    scheduleId      = it.scheduleId,
                    doctorId        = it.doctorId,
                    doctorName      = it.doctorName,
                    slotDate        = it.slotDate.toString(),
                    slotTime        = it.slotTime.toString(),
                    durationMinutes = it.durationMinutes.toInt(),
                    isAvailable     = it.isAvailable
                )
            })
        }
    }
}

fun Route.appointmentRoutes(
    appointmentRepository: AppointmentRepositoryImpl,
    notificationService: NotificationService,
    logRepository: LogRepositoryImpl
) {
    authenticate("jwt-auth") {
        route("/appointments") {

            post {
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<CreateAppointmentRequest>()

                val appointmentId = appointmentRepository.create(
                    userId     = userId,
                    doctorId   = req.doctorId,
                    scheduleId = req.scheduleId,
                    notes      = req.notes
                )

                val appointment = appointmentRepository.findById(appointmentId)!!

                notificationService.notifyAppointmentCreated(
                    userId        = userId,
                    appointmentId = appointmentId,
                    doctorName    = appointment.doctorName,
                    date          = appointment.slotDate.toString(),
                    time          = appointment.slotTime.toString()
                )

                logRepository.log(
                    userId = userId,
                    action = "APPOINTMENT_CREATE",
                    meta   = """{"appointmentId":"$appointmentId","doctorId":${req.doctorId}}"""
                )

                call.respond(HttpStatusCode.Created, appointment.toDto())
            }

            get {
                val userId = UUID.fromString(call.getUserId())
                val status = call.request.queryParameters["status"]
                val appointments = appointmentRepository.findByUser(userId, status)
                call.respond(appointments.map { it.toDto() })
            }

            get("/{id}") {
                val userId        = UUID.fromString(call.getUserId())
                val appointmentId = UUID.fromString(call.parameters["id"]!!)
                val appointment   = appointmentRepository.findById(appointmentId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                if (appointment.userId != userId)
                    return@get call.respond(HttpStatusCode.Forbidden)

                call.respond(appointment.toDto())
            }

            delete("/{id}") {
                val userId        = UUID.fromString(call.getUserId())
                val appointmentId = UUID.fromString(call.parameters["id"]!!)
                val appointment   = appointmentRepository.findById(appointmentId)

                if (appointment == null || appointment.userId != userId)
                    return@delete call.respond(HttpStatusCode.NotFound)

                val appt = appointmentRepository.findById(appointmentId)
                if (appt != null) {
                    val slotDateTime = java.time.OffsetDateTime.of(
                        appt.slotDate, appt.slotTime,
                        java.time.ZoneOffset.of("+03:00")
                    )
                    val hoursUntil = java.time.Duration.between(
                        java.time.OffsetDateTime.now(), slotDateTime).toHours()
                    if (hoursUntil < 24) {
                        return@delete call.respond(HttpStatusCode.UnprocessableEntity,
                            mapOf("message" to "Отмена невозможна менее чем за 24 часа до приёма"))
                    }
                }
                val cancelled = appointmentRepository.cancel(appointmentId, userId)
                if (!cancelled)
                    return@delete call.respond(HttpStatusCode.Conflict,
                        MessageResponse("Запись уже отменена или завершена"))

                notificationService.notifyAppointmentCancelled(
                    userId        = userId,
                    appointmentId = appointmentId,
                    doctorName    = appointment.doctorName,
                    date          = appointment.slotDate.toString()
                )

                logRepository.log(userId, "APPOINTMENT_CANCEL",
                    meta = """{"appointmentId":"$appointmentId"}""")

                call.respond(MessageResponse("Запись отменена"))
            }
        }
    }
}

private fun com.eva.domain.models.Appointment.toDto() = AppointmentResponse(
    appointmentId      = appointmentId.toString(),
    doctorId           = doctorId,
    doctorName         = doctorName,
    specializationName = specializationName,
    clinicName         = clinicName,
    clinicAddress      = clinicAddress,
    slotDate           = slotDate.toString(),
    slotTime           = slotTime.toString(),
    durationMinutes    = 30,
    status             = status,
    notes              = notes,
    doctorConclusion   = doctorConclusion,
    createdAt          = createdAt.toString()
)

fun Route.symptomsRoutes(
    symptomsRepository: SymptomsRepositoryImpl,
    aiService: AiService
) {
    authenticate("jwt-auth") {
        route("/symptoms") {

            post("/analyze") {
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<AnalyzeSymptomsRequest>()

                require(req.symptomsText.length >= 20) { "Описание симптомов слишком короткое (минимум 20 символов)" }
                require(req.symptomsText.length <= 5000) { "Описание симптомов слишком длинное (максимум 5000 символов)" }

                // Сохранить запрос
                val requestId = symptomsRepository.create(userId, req.symptomsText)

                // Получить анализ от AI
                val aiResult = aiService.analyze(req.symptomsText)

                // Сохранить ответ
                symptomsRepository.saveAiResponse(
                    requestId       = requestId,
                    diagnosis       = aiResult.diagnosis,
                    recommendations = aiResult.recommendations,
                    urgency         = aiResult.urgency,
                    modelVersion    = aiResult.modelVersion,
                    confidence      = aiResult.confidence,
                    processingMs    = aiResult.processingMs,
                    rawResponse     = aiResult.rawResponse
                )

                call.respond(AnalyzeSymptomsResponse(
                    requestId          = requestId.toString(),
                    diagnosis          = aiResult.diagnosis,
                    recommendations    = aiResult.recommendations,
                    urgency            = aiResult.urgency,
                    confidence         = aiResult.confidence.toPlainString(),
                    modelVersion       = aiResult.modelVersion,
                    processingMs       = aiResult.processingMs,
                    isStub             = aiResult.isStub,
                    specializationName = aiResult.specializationName
                ))
            }

            get("/history") {
                val userId   = UUID.fromString(call.getUserId())
                val requests = symptomsRepository.findByUser(userId)
                call.respond(requests.map { req ->
                    val aiResp = if (req.hasResponse) symptomsRepository.getAiResponse(req.requestId) else null
                    SymptomsHistoryResponse(
                        requestId    = req.requestId.toString(),
                        symptomsText = req.symptomsText,
                        hasResponse  = req.hasResponse,
                        createdAt    = req.createdAt.toString(),
                        aiResponse   = aiResp?.let {
                            AiResponseDto(
                                diagnosis       = it.diagnosis,
                                recommendations = it.recommendations,
                                urgency         = it.urgency,
                                confidence      = it.confidence.toPlainString(),
                                modelVersion    = it.modelVersion
                            )
                        }
                    )
                })
            }
        }
    }
}

fun Route.notificationRoutes(notificationRepository: NotificationRepositoryImpl) {
    authenticate("jwt-auth") {
        route("/notifications") {

            get {
                val userId     = UUID.fromString(call.getUserId())
                val onlyUnread = call.request.queryParameters["unread"] == "true"
                val list       = notificationRepository.findByUser(userId, onlyUnread)
                call.respond(list.map {
                    NotificationResponse(
                        notificationId = it.notificationId.toString(),
                        title          = it.title,
                        body           = it.body,
                        isRead         = it.isRead,
                        channel        = it.channel,
                        appointmentId  = it.appointmentId?.toString(),
                        createdAt      = it.createdAt.toString()
                    )
                })
            }

            post("/{id}/read") {
                val userId         = UUID.fromString(call.getUserId())
                val notificationId = UUID.fromString(call.parameters["id"]!!)
                notificationRepository.markRead(notificationId, userId)
                call.respond(MessageResponse("Уведомление отмечено прочитанным"))
            }

            post("/read-all") {
                val userId = UUID.fromString(call.getUserId())
                val count  = notificationRepository.markAllRead(userId)
                call.respond(MessageResponse("Отмечено прочитанными: $count"))
            }

            post("/fcm-token") {
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<RegisterFcmTokenRequest>()
                // Сохранить токен в fcm_tokens (упрощённая реализация — INSERT OR UPDATE)
                org.jetbrains.exposed.sql.transactions.transaction {
                    com.eva.data.tables.FcmTokensTable.upsert(
                        com.eva.data.tables.FcmTokensTable.token
                    ) {
                        it[com.eva.data.tables.FcmTokensTable.userId]   = userId
                        it[com.eva.data.tables.FcmTokensTable.token]    = req.token
                        it[com.eva.data.tables.FcmTokensTable.deviceId] = req.deviceId
                        it[com.eva.data.tables.FcmTokensTable.platform] = req.platform
                        it[com.eva.data.tables.FcmTokensTable.isActive] = true
                        it[com.eva.data.tables.FcmTokensTable.updatedAt] = java.time.OffsetDateTime.now()
                    }
                }
                call.respond(MessageResponse("FCM токен зарегистрирован"))
            }
        }
    }
}

fun Route.healthRoute() {
    get("/health") {
        call.respond(HealthResponse())
    }
}