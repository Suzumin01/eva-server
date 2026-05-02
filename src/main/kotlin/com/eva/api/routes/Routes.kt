package com.eva.api.routes

import com.eva.api.dto.*
import com.eva.data.repository.*
import com.eva.plugins.getUserId
import com.eva.util.canCancelAppointment
import com.eva.plugins.getUserRole
import io.ktor.server.plugins.ratelimit.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.eva.service.AiService
import com.eva.service.NotificationService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID

private val routesLogger = LoggerFactory.getLogger("com.eva.api.routes.Routes")

private const val DEFAULT_PAGE_SIZE = 20
private const val MAX_PAGE_SIZE     = 50
private const val MAX_REVIEW_LEN    = 5000

fun Route.specializationRoutes(specializationRepository: SpecializationRepositoryImpl) {
    route("/specializations") {
        get {
            call.respond(specializationRepository.findAll())
        }
    }
}

fun Route.doctorRoutes(
    doctorRepository: DoctorRepositoryImpl,
    clinicRepository: ClinicRepositoryImpl
) {
    route("/doctors") {
        get {
            val specializationId = call.request.queryParameters["specializationId"]?.toShortOrNull()
            val clinicId         = call.request.queryParameters["clinicId"]?.toIntOrNull()
            val search           = call.request.queryParameters["search"]
            val limit            = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
            val offset           = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L

            val doctors = doctorRepository.findAll(specializationId, clinicId, search, limit, offset)
            val total   = doctorRepository.countAll(specializationId, clinicId, search)
            call.respond(DoctorListResponse(
                doctors = doctors.map { it.toDto() },
                total   = total.toInt()
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

                if (req.rating !in 1..5)
                    return@post call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Оценка должна быть от 1 до 5"))
                if (req.comment != null && req.comment.length > MAX_REVIEW_LEN)
                    return@post call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Текст отзыва не должен превышать $MAX_REVIEW_LEN символов"))

                if (!doctorRepository.hasCompletedAppointment(doctorId, userId)) {
                    return@post call.respond(HttpStatusCode.Forbidden,
                        mapOf("message" to "Отзыв можно оставить только после завершённого приёма"))
                }
                if (doctorRepository.hasReviewed(doctorId, userId)) {
                    return@post call.respond(HttpStatusCode.Conflict,
                        mapOf("message" to "Вы уже оставляли отзыв этому врачу"))
                }

                doctorRepository.addReview(doctorId, userId, req.rating.toShort(), req.comment)
                call.respond(HttpStatusCode.Created, mapOf("message" to "Отзыв добавлен"))
            }

            patch("/reviews/{reviewId}") {
                val reviewId = call.parameters["reviewId"]?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                } ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<UpdateReviewRequest>()

                if (req.rating !in 1..5)
                    return@patch call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Оценка должна быть от 1 до 5"))
                if (req.comment != null && req.comment.length > MAX_REVIEW_LEN)
                    return@patch call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Текст отзыва не должен превышать $MAX_REVIEW_LEN символов"))

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

                doctorRepository.deleteReview(reviewId, userId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound,
                        mapOf("message" to "Отзыв не найден или недоступен для удаления"))

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
            call.respond(clinicRepository.findAll().map { it.toDto() })
        }
        get("/{id}/logo") {
            val id   = call.parameters["id"]?.toIntOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            val file = listOf("jpg", "png")
                .map { File("${clinicLogoDir()}/$id.$it") }
                .firstOrNull { it.exists() }
                ?: return@get call.respond(HttpStatusCode.NotFound)
            val ct = if (file.extension == "png") ContentType.Image.PNG else ContentType.Image.JPEG
            call.respond(LocalFileContent(file, ct))
        }
    }

    authenticate("jwt-auth") {
        route("/clinics") {
            post("/{id}/logo") {
                val clinicId = call.parameters["id"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                val multipart = call.receiveMultipart()
                var ext = ""; var savedFile: File? = null; var overLimit = false
                multipart.forEachPart { part: PartData ->
                    if (part is PartData.FileItem && part.name == "logo") {
                        val orig = part.originalFileName ?: ""
                        ext = when {
                            orig.endsWith(".jpg",  ignoreCase = true) -> "jpg"
                            orig.endsWith(".jpeg", ignoreCase = true) -> "jpg"
                            orig.endsWith(".png",  ignoreCase = true) -> "png"
                            else -> ""
                        }
                        if (ext.isNotEmpty()) {
                            val file = File("${clinicLogoDir()}/$clinicId.$ext")
                            var written = 0L
                            part.streamProvider().use { inp ->
                                file.outputStream().use { out ->
                                    val buf = ByteArray(8192); var n = 0
                                    while (inp.read(buf).also { n = it } != -1) {
                                        written += n
                                        if (written > 5 * 1024 * 1024) { overLimit = true; break }
                                        out.write(buf, 0, n)
                                    }
                                }
                            }
                            if (overLimit) file.delete() else savedFile = file
                        }
                    }
                    part.dispose()
                }
                when {
                    overLimit     -> call.respond(HttpStatusCode.PayloadTooLarge,
                        mapOf("message" to "Файл слишком большой (макс. 5 МБ)"))
                    ext.isEmpty() -> call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Поддерживаются только jpg/jpeg/png"))
                    savedFile == null -> call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Поле 'logo' не найдено"))
                    else -> {
                        listOf("jpg", "png").filter { it != ext }
                            .forEach { File("${clinicLogoDir()}/$clinicId.$it").delete() }
                        val logoUrl = "/api/v1/clinics/$clinicId/logo"
                        clinicRepository.updateLogoUrl(clinicId, logoUrl)
                        call.respond(mapOf("logoUrl" to logoUrl))
                    }
                }
            }
        }
    }
}

private fun clinicLogoDir(): String {
    val base = System.getenv("UPLOAD_DIR").takeIf { !it.isNullOrBlank() }
        ?: File("uploads").absolutePath
    return File("$base/clinic_logos").also { it.mkdirs() }.absolutePath
}

private fun com.eva.domain.models.Clinic.toDto() = ClinicResponse(
    clinicId     = clinicId,
    clinicName   = clinicName,
    address      = address,
    phone        = phone,
    latitude     = latitude?.toString(),
    longitude    = longitude?.toString(),
    logoUrl      = logoUrl,
    rating       = rating?.toString(),
    doctorsCount = doctorsCount
)

private fun com.eva.domain.models.Doctor.toDto() = DoctorResponse(
    doctorId           = doctorId,
    userId             = userId?.toString(),
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
            val date = dateStr?.let {
                runCatching { java.time.LocalDate.parse(it) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Некорректный формат date (ожидается YYYY-MM-DD)"))
                }
            }
            val dateTo = dateToStr?.let {
                runCatching { java.time.LocalDate.parse(it) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Некорректный формат dateTo (ожидается YYYY-MM-DD)"))
                }
            }

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
    logRepository: LogRepositoryImpl,
    timezone: String = "Europe/Moscow"
) {
    val zoneId = java.time.ZoneId.of(timezone)
    suspend fun ApplicationCall.parseAppointmentId(): UUID? {
        val id = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (id == null) respond(HttpStatusCode.BadRequest, mapOf("message" to "Некорректный ID записи"))
        return id
    }

    authenticate("jwt-auth") {
        route("/appointments") {

            post {
                val userId = UUID.fromString(call.getUserId())
                val req    = call.receive<CreateAppointmentRequest>()

                val appointmentId = appointmentRepository.create(
                    userId     = userId,
                    scheduleId = req.scheduleId,
                    notes      = req.notes
                )

                val appointment = appointmentRepository.findById(appointmentId)
                    ?: return@post call.respond(HttpStatusCode.InternalServerError,
                        mapOf("message" to "Ошибка при создании записи"))

                call.application.launch(Dispatchers.IO) {
                    try {
                        notificationService.notifyAppointmentCreated(
                            userId        = userId,
                            appointmentId = appointmentId,
                            doctorName    = appointment.doctorName,
                            date          = appointment.slotDate.toString(),
                            time          = appointment.slotTime.toString()
                        )
                    } catch (e: Exception) {
                        routesLogger.error("Ошибка отправки уведомления о записи $appointmentId: ${e.message}", e)
                    }
                }

                logRepository.log(
                    userId = userId,
                    action = "APPOINTMENT_CREATE",
                    meta   = buildJsonObject {
                        put("appointmentId", appointmentId.toString())
                        put("doctorId", req.doctorId)
                    }.toString()
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
                val appointmentId = call.parseAppointmentId() ?: return@get
                val appointment   = appointmentRepository.findById(appointmentId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)

                if (appointment.userId != userId)
                    return@get call.respond(HttpStatusCode.Forbidden)

                call.respond(appointment.toDto())
            }

            patch("/{id}/complete") {
                if (call.getUserRole() != "admin")
                    return@patch call.respond(HttpStatusCode.Forbidden,
                        mapOf("message" to "Недостаточно прав"))
                val appointmentId = call.parseAppointmentId() ?: return@patch
                val updated = appointmentRepository.complete(appointmentId)
                if (!updated) return@patch call.respond(HttpStatusCode.Conflict,
                    MessageResponse("Запись не найдена или уже завершена/отменена"))
                call.respond(MessageResponse("Статус записи обновлён: completed"))
            }

            patch("/{id}/conclusion") {
                if (call.getUserRole() != "admin")
                    return@patch call.respond(HttpStatusCode.Forbidden,
                        mapOf("message" to "Недостаточно прав"))
                val appointmentId = call.parseAppointmentId() ?: return@patch
                val req = call.receive<SetConclusionRequest>()
                if (req.conclusion.isBlank())
                    return@patch call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Заключение не может быть пустым"))
                val updated = appointmentRepository.setConclusion(appointmentId, req.conclusion)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound,
                    MessageResponse("Запись не найдена"))
                call.respond(MessageResponse("Заключение сохранено"))
            }

            delete("/{id}") {
                val userId        = UUID.fromString(call.getUserId())
                val appointmentId = call.parseAppointmentId() ?: return@delete
                val appointment   = appointmentRepository.findById(appointmentId)

                if (appointment == null || appointment.userId != userId)
                    return@delete call.respond(HttpStatusCode.NotFound)

                val slotDateTime = java.time.ZonedDateTime.of(
                    appointment.slotDate, appointment.slotTime, zoneId
                )
                if (!canCancelAppointment(slotDateTime, java.time.ZonedDateTime.now(zoneId))) {
                    return@delete call.respond(HttpStatusCode.UnprocessableEntity,
                        mapOf("message" to "Отмена невозможна менее чем за 24 часа до приёма"))
                }
                val cancelled = appointmentRepository.cancel(appointmentId, userId)
                if (!cancelled)
                    return@delete call.respond(HttpStatusCode.Conflict,
                        MessageResponse("Запись уже отменена или завершена"))

                call.application.launch(Dispatchers.IO) {
                    try {
                        notificationService.notifyAppointmentCancelled(
                            userId        = userId,
                            appointmentId = appointmentId,
                            doctorName    = appointment.doctorName,
                            date          = appointment.slotDate.toString()
                        )
                    } catch (e: Exception) {
                        routesLogger.error("Ошибка отправки уведомления об отмене $appointmentId: ${e.message}", e)
                    }
                }

                logRepository.log(userId, "APPOINTMENT_CANCEL",
                    meta = buildJsonObject { put("appointmentId", appointmentId.toString()) }.toString())

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
    durationMinutes    = durationMinutes.toInt(),
    status             = status,
    notes              = notes,
    doctorConclusion   = doctorConclusion,
    patientHealthInfo  = patientHealthInfo,
    createdAt          = createdAt.toString()
)

private const val DAILY_AI_LIMIT = 10

fun Route.symptomsRoutes(
    symptomsRepository: SymptomsRepositoryImpl,
    specializationRepository: com.eva.data.repository.SpecializationRepositoryImpl,
    aiService: AiService
) {
    authenticate("jwt-auth") {
        route("/symptoms") {

            rateLimit(RateLimitName("quota_check")) {
                get("/quota") {
                    val userId = UUID.fromString(call.getUserId())
                    val used   = symptomsRepository.countTodayByUser(userId)
                    call.respond(SymptomsQuotaResponse(
                        used      = used,
                        limit     = DAILY_AI_LIMIT,
                        remaining = maxOf(0, DAILY_AI_LIMIT - used)
                    ))
                }
            }

            rateLimit(RateLimitName("ai_analyze")) {
                post("/analyze") {
                    val userId = UUID.fromString(call.getUserId())
                    val req    = call.receive<AnalyzeSymptomsRequest>()

                    if (req.symptomsText.length < 20)
                        return@post call.respond(HttpStatusCode.BadRequest,
                            mapOf("message" to "Описание симптомов слишком короткое (минимум 20 символов)"))
                    if (req.symptomsText.length > 5000)
                        return@post call.respond(HttpStatusCode.BadRequest,
                            mapOf("message" to "Описание симптомов слишком длинное (максимум 5000 символов)"))

                    val specializations = specializationRepository.findAll().map { it.name }
                    val aiResult        = aiService.analyze(req.symptomsText, specializations)
                    val requestId       = symptomsRepository.create(userId, req.symptomsText)

                    symptomsRepository.saveAiResponse(
                        requestId       = requestId,
                        title           = aiResult.title,
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
                        title              = aiResult.title,
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
                                title           = it.title,
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

fun Route.notificationRoutes(
    notificationRepository: NotificationRepositoryImpl,
    fcmTokenRepository: com.eva.data.repository.FcmTokenRepositoryImpl
) {
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
                val notificationId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Некорректный ID уведомления"))
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
                fcmTokenRepository.saveToken(
                    userId   = userId,
                    token    = req.token,
                    deviceId = req.deviceId
                )
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