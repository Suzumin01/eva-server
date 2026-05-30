package com.eva.api.routes

import at.favre.lib.crypto.bcrypt.BCrypt
import com.eva.api.dto.*
import com.eva.data.repository.*
import com.eva.plugins.requireRole
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

fun Route.adminRoutes(
    userRepository: UserRepositoryImpl,
    doctorRepository: DoctorRepositoryImpl,
    clinicRepository: ClinicRepositoryImpl,
    appointmentRepository: AppointmentRepositoryImpl,
    scheduleRepository: ScheduleRepositoryImpl,
    statsRepository: AdminStatsRepository,
    specializationRepository: SpecializationRepositoryImpl,
    documentRepository: DocumentRepositoryImpl
) {
    authenticate("jwt-auth") {
        route("/admin") {

            get("/stats") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val s = statsRepository.getStats()
                call.respond(StatsResponse(
                    totalUsers          = s.totalUsers,
                    totalDoctors        = s.totalDoctors,
                    appointmentsToday   = s.appointmentsToday,
                    appointmentsWeek    = s.appointmentsWeek,
                    topSpecializations  = s.topSpecializations.map { SpecializationStatDto(it.first, it.second) }
                ))
            }

            get("/stats/chart") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(7, 90) ?: 7
                val data = statsRepository.getAppointmentsByDay(days, false)
                call.respond(data.map { DayCountDto(it.first, it.second) })
            }

            get("/stats/statuses") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val data = statsRepository.getAppointmentStatuses()
                call.respond(data.map { NameCountDto(it.first, it.second) })
            }

            get("/stats/doctor-load") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5
                val data = statsRepository.getDoctorLoad(limit)
                call.respond(data.map { NameCountDto(it.first, it.second) })
            }

            get("/stats/ai") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val (total, last30, urgency) = statsRepository.getAiStats()
                call.respond(AiStatsResponse(
                    totalRequests       = total,
                    requestsLast30Days  = last30,
                    urgencyDistribution = urgency.map { NameCountDto(it.first, it.second) }
                ))
            }

            get("/users") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val search = call.request.queryParameters["search"]
                val role   = call.request.queryParameters["role"]
                val limit  = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val offset = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L
                val users  = userRepository.findAll(search, role, limit, offset)
                val total  = userRepository.countAll(search, role)
                call.respond(AdminUserListResponse(
                    users = users.map { u ->
                        AdminUserResponse(
                            userId          = u.userId.toString(),
                            fullName        = u.fullName,
                            email           = u.email,
                            phone           = u.phone,
                            role            = u.roleName,
                            isActive        = u.isActive,
                            avatarUrl       = u.avatarUrl,
                            dateOfBirth     = u.dateOfBirth?.toString(),
                            allergies       = u.allergies,
                            chronicDiseases = u.chronicDiseases,
                            createdAt       = u.createdAt.toString(),
                            lastLoginAt     = u.lastLoginAt?.toString()
                        )
                    },
                    total = total
                ))
            }

            patch("/users/{userId}/active") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный userId"))
                }
                val req     = call.receive<SetActiveRequest>()
                val updated = userRepository.setActive(userId, req.active)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Пользователь не найден"))
                call.respond(MessageResponse(if (req.active) "Пользователь активирован" else "Пользователь заблокирован"))
            }

            patch("/users/{userId}/role") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный userId"))
                }
                val req    = call.receive<UpdateRoleRequest>()
                val roleId = userRepository.findRoleIdByName(req.role)
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Роль не найдена: ${req.role}"))
                val updated = userRepository.updateRole(userId, roleId)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Пользователь не найден"))
                call.respond(MessageResponse("Роль обновлена"))
            }

            patch("/users/{userId}") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный userId"))
                }
                val req = call.receive<UpdateAdminUserRequest>()
                if (req.fullName != null) {
                    if (req.fullName.trim().length < 2)
                        return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("ФИО должно быть минимум 2 символа"))
                    if (req.fullName.trim().length > 200)
                        return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("ФИО слишком длинное"))
                }
                if (req.email != null) {
                    if (!req.email.contains("@") || req.email.length > 255)
                        return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный email"))
                    if (userRepository.existsByEmailExcluding(req.email.trim(), userId))
                        return@patch call.respond(HttpStatusCode.Conflict, MessageResponse("Email уже занят"))
                }
                val parsedDob = req.dateOfBirth?.takeIf { it.isNotBlank() }?.let {
                    runCatching { java.time.LocalDate.parse(it) }.getOrElse {
                        return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный формат даты (ожидается YYYY-MM-DD)"))
                    }
                }
                val updated = userRepository.updateProfile(
                    userId          = userId,
                    fullName        = req.fullName?.trim(),
                    phone           = req.phone?.trim(),
                    dateOfBirth     = parsedDob,
                    allergies       = req.allergies?.trim(),
                    chronicDiseases = req.chronicDiseases?.trim(),
                    clearDateOfBirth = req.dateOfBirth == "",
                    clearAllergies   = req.allergies == "",
                    clearChronic     = req.chronicDiseases == ""
                ).also {
                    if (req.email != null) userRepository.adminUpdateProfile(userId, null, req.email.trim(), null)
                }
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Пользователь не найден"))
                call.respond(MessageResponse("Пользователь обновлён"))
            }

            post("/doctors") {
                if (!call.requireRole("admin"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val req = call.receive<CreateDoctorRequest>()
                require(req.fullName.trim().length >= 2)   { "ФИО должно быть минимум 2 символа" }
                require(req.fullName.trim().length <= 200) { "ФИО слишком длинное" }
                val doctorId = doctorRepository.createDoctor(
                    fullName         = req.fullName.trim(),
                    clinicId         = req.clinicId,
                    specializationId = req.specializationId.toShort(),
                    bio              = req.bio,
                    photoUrl         = req.photoUrl,
                    experienceYears  = req.experienceYears?.toShort()
                )
                call.respond(HttpStatusCode.Created, mapOf("doctorId" to doctorId, "message" to "Врач создан"))
            }

            patch("/doctors/{doctorId}") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.parameters["doctorId"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный doctorId"))
                val req     = call.receive<UpdateDoctorRequest>()
                val updated = doctorRepository.updateDoctor(
                    doctorId         = doctorId,
                    fullName         = req.fullName,
                    clinicId         = req.clinicId,
                    specializationId = req.specializationId?.toShort(),
                    bio              = req.bio,
                    photoUrl         = req.photoUrl,
                    experienceYears  = req.experienceYears?.toShort()
                )
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Врач не найден"))
                call.respond(MessageResponse("Данные врача обновлены"))
            }

            delete("/doctors/{doctorId}") {
                if (!call.requireRole("admin"))
                    return@delete call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.parameters["doctorId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный doctorId"))
                val updated = doctorRepository.deactivateDoctor(doctorId)
                if (!updated) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("Врач не найден"))
                call.respond(MessageResponse("Врач деактивирован"))
            }

            post("/doctors/{doctorId}/account") {
                if (!call.requireRole("admin"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.parameters["doctorId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный doctorId"))
                val req = call.receive<CreateDoctorAccountRequest>()
                require(req.email.contains("@"))    { "Некорректный email" }
                require(req.email.length <= 255)    { "Email слишком длинный" }
                require(req.password.length >= 8)   { "Пароль минимум 8 символов" }
                require(req.password.length <= 128) { "Пароль слишком длинный" }

                val doctor = doctorRepository.findById(doctorId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("Врач не найден"))

                val doctorRoleId = userRepository.findRoleIdByName("doctor")
                    ?: return@post call.respond(HttpStatusCode.InternalServerError, MessageResponse("Роль doctor не найдена"))

                val hash   = BCrypt.withDefaults().hashToString(12, req.password.toCharArray())
                val userId = userRepository.create(
                    fullName   = req.fullName ?: doctor.fullName,
                    email      = req.email.trim().lowercase(),
                    phone      = null,
                    passwordHash = hash,
                    roleId     = doctorRoleId
                )
                doctorRepository.linkUser(doctorId, userId)
                call.respond(HttpStatusCode.Created, mapOf("userId" to userId.toString(), "message" to "Аккаунт врача создан"))
            }

            post("/clinics") {
                if (!call.requireRole("admin"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val req = call.receive<CreateClinicRequest>()
                require(req.clinicName.isNotBlank())          { "Название клиники обязательно" }
                require(req.clinicName.trim().length <= 300) { "Название клиники слишком длинное" }
                require(req.address.isNotBlank())             { "Адрес обязателен" }
                require(req.address.trim().length <= 500)    { "Адрес слишком длинный" }
                val clinicId = clinicRepository.create(req.clinicName.trim(), req.address.trim(), req.phone, req.website)
                call.respond(HttpStatusCode.Created, mapOf("clinicId" to clinicId, "message" to "Клиника создана"))
            }

            patch("/clinics/{clinicId}") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val clinicId = call.parameters["clinicId"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный clinicId"))
                val req = call.receive<UpdateClinicRequest>()
                val updated = clinicRepository.update(clinicId, req.clinicName, req.address, req.phone, req.website)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Клиника не найдена"))
                call.respond(MessageResponse("Клиника обновлена"))
            }

            delete("/clinics/{clinicId}") {
                if (!call.requireRole("admin"))
                    return@delete call.respond(HttpStatusCode.Forbidden)
                val clinicId = call.parameters["clinicId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный clinicId"))
                val updated = clinicRepository.deactivate(clinicId)
                if (!updated) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("Клиника не найдена"))
                call.respond(MessageResponse("Клиника деактивирована"))
            }

            get("/appointments") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val status   = call.request.queryParameters["status"]
                val doctorId = call.request.queryParameters["doctorId"]?.toIntOrNull()
                val dateFrom = call.request.queryParameters["dateFrom"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val dateTo   = call.request.queryParameters["dateTo"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val limit    = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
                val offset   = call.request.queryParameters["offset"]?.toLongOrNull() ?: 0L

                val list  = appointmentRepository.findAll(status, doctorId, dateFrom, dateTo, limit, offset)
                val total = appointmentRepository.countAll(status, doctorId, dateFrom, dateTo)
                call.respond(AdminAppointmentListResponse(
                    appointments = list.map { it.toAdminDto() },
                    total = total
                ))
            }

            patch("/appointments/{id}/status") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val appointmentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                }
                val req = call.receive<UpdateStatusRequest>()
                if (req.status !in listOf("scheduled", "completed", "cancelled"))
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Недопустимый статус"))
                val appt = appointmentRepository.findById(appointmentId)
                    ?: return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Запись не найдена"))
                val updated = appointmentRepository.updateStatus(appointmentId, appt.doctorId, req.status)
                if (!updated) return@patch call.respond(HttpStatusCode.Conflict, MessageResponse("Не удалось обновить статус"))
                call.respond(MessageResponse("Статус обновлён: ${req.status}"))
            }

            get("/reviews") {
                if (!call.requireRole("admin"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.request.queryParameters["doctorId"]?.toIntOrNull()
                val hidden   = call.request.queryParameters["hidden"]?.toBooleanStrictOrNull()
                val reviews  = doctorRepository.findAllReviews(doctorId, hidden)
                call.respond(reviews.map {
                    AdminReviewResponse(
                        reviewId     = it.reviewId.toString(),
                        doctorId     = it.doctorId,
                        doctorName   = it.doctorName,
                        userId       = it.userId.toString(),
                        userFullName = it.userFullName,
                        rating       = it.rating.toInt(),
                        comment      = it.comment,
                        isHidden     = it.isHidden,
                        createdAt    = it.createdAt.toString()
                    )
                })
            }

            patch("/reviews/{reviewId}/hide") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val reviewId = runCatching { UUID.fromString(call.parameters["reviewId"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный reviewId"))
                }
                val req     = call.receive<Map<String, Boolean>>()
                val hide    = req["hidden"] ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Поле hidden обязательно"))
                val updated = doctorRepository.hideReview(reviewId, hide)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Отзыв не найден"))
                call.respond(MessageResponse(if (hide) "Отзыв скрыт" else "Отзыв показан"))
            }

            delete("/reviews/{reviewId}") {
                if (!call.requireRole("admin"))
                    return@delete call.respond(HttpStatusCode.Forbidden)
                val reviewId = runCatching { UUID.fromString(call.parameters["reviewId"]) }.getOrElse {
                    return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный reviewId"))
                }
                val deleted = doctorRepository.deleteReviewAdmin(reviewId)
                if (!deleted) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("Отзыв не найден"))
                call.respond(MessageResponse("Отзыв удалён"))
            }

            post("/schedules") {
                if (!call.requireRole("admin"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val req = call.receive<CreateScheduleRequest>()
                val doctorId = req.doctorId
                    ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("doctorId обязателен для admin"))
                val slotDate = runCatching { LocalDate.parse(req.slotDate) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректная дата"))
                }
                val slotTime = runCatching { LocalTime.parse(req.slotTime, timeFmt) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректное время (HH:mm)"))
                }
                val scheduleId = scheduleRepository.create(doctorId, slotDate, slotTime, req.durationMinutes.toShort())
                call.respond(HttpStatusCode.Created, mapOf("scheduleId" to scheduleId, "message" to "Слот создан"))
            }

            post("/schedules/bulk") {
                if (!call.requireRole("admin"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val req = call.receive<BulkCreateScheduleRequest>()
                val doctorId = req.doctorId
                    ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("doctorId обязателен"))
                val dateFrom = runCatching { LocalDate.parse(req.dateFrom) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный dateFrom"))
                }
                val dateTo = runCatching { LocalDate.parse(req.dateTo) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный dateTo"))
                }
                if (dateTo < dateFrom) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("dateTo не может быть раньше dateFrom"))
                val times = req.times.mapNotNull { runCatching { LocalTime.parse(it, timeFmt) }.getOrNull() }
                if (times.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Нет корректных значений times"))

                var created = 0; var skipped = 0
                var date = dateFrom
                while (!date.isAfter(dateTo)) {
                    for (t in times) {
                        runCatching { scheduleRepository.create(doctorId, date, t, req.durationMinutes.toShort()) }
                            .onSuccess { created++ }
                            .onFailure { skipped++ }
                    }
                    date = date.plusDays(1)
                }
                call.respond(BulkCreateResponse(created, skipped))
            }

            delete("/schedules/{scheduleId}") {
                if (!call.requireRole("admin"))
                    return@delete call.respond(HttpStatusCode.Forbidden)
                val scheduleId = call.parameters["scheduleId"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный scheduleId"))
                val deleted = scheduleRepository.delete(scheduleId)
                if (!deleted) return@delete call.respond(HttpStatusCode.Conflict, MessageResponse("Слот не найден или уже занят"))
                call.respond(MessageResponse("Слот удалён"))
            }

            post("/specializations") {
                if (!call.requireRole("admin"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val req = call.receive<CreateSpecializationRequest>()
                require(req.name.trim().isNotBlank()) { "Название специализации обязательно" }
                val id = specializationRepository.create(req.name, req.description)
                call.respond(HttpStatusCode.Created, mapOf("specializationId" to id, "message" to "Специализация создана"))
            }

            patch("/specializations/{id}") {
                if (!call.requireRole("admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                val req     = call.receive<UpdateSpecializationRequest>()
                val updated = specializationRepository.update(id, req.name, req.description)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Специализация не найдена"))
                call.respond(MessageResponse("Специализация обновлена"))
            }

            delete("/specializations/{id}") {
                if (!call.requireRole("admin"))
                    return@delete call.respond(HttpStatusCode.Forbidden)
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                val deleted = runCatching { specializationRepository.delete(id) }.getOrElse {
                    return@delete call.respond(HttpStatusCode.Conflict, MessageResponse("Нельзя удалить: есть связанные врачи"))
                }
                if (!deleted) return@delete call.respond(HttpStatusCode.NotFound, MessageResponse("Специализация не найдена"))
                call.respond(MessageResponse("Специализация удалена"))
            }

            post("/users/{userId}/photo") {
                if (!call.requireRole("admin")) return@post call.respond(HttpStatusCode.Forbidden)
                val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный userId"))
                }
                val (ext, savedFile, overLimit) = receivePhotoUpload(
                    call.receiveMultipart(), AVATAR_DIR, userId.toString()
                )
                when {
                    overLimit    -> call.respond(HttpStatusCode.PayloadTooLarge, MessageResponse("Файл слишком большой (макс. 5 МБ)"))
                    ext.isEmpty() -> call.respond(HttpStatusCode.BadRequest,  MessageResponse("Поддерживаются только jpg/jpeg/png"))
                    savedFile == null -> call.respond(HttpStatusCode.BadRequest, MessageResponse("Файл не получен"))
                    else -> {
                        listOf("jpg", "png").filter { it != ext }.forEach { File("$AVATAR_DIR/$userId.$it").delete() }
                        resizeAvatar(savedFile, 512)
                        val url = "/api/v1/auth/photo/$userId"
                        userRepository.updateAvatarUrl(userId, url)
                        doctorRepository.findIdByUserId(userId)?.let { doctorId ->
                            listOf("jpg", "png").filter { it != ext }.forEach { File("${doctorPhotoDir()}/$doctorId.$it").delete() }
                            savedFile.copyTo(File("${doctorPhotoDir()}/$doctorId.$ext"), overwrite = true)
                            doctorRepository.updateDoctor(doctorId, null, null, null, null, "/api/v1/doctors/$doctorId/photo", null)
                        }
                        call.respond(mapOf("avatarUrl" to url))
                    }
                }
            }

            delete("/users/{userId}/photo") {
                if (!call.requireRole("admin")) return@delete call.respond(HttpStatusCode.Forbidden)
                val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrElse {
                    return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный userId"))
                }
                listOf("jpg", "png").forEach { File("$AVATAR_DIR/$userId.$it").delete() }
                userRepository.clearAvatarUrl(userId)
                doctorRepository.findIdByUserId(userId)?.let { doctorId ->
                    listOf("jpg", "png").forEach { File("${doctorPhotoDir()}/$doctorId.$it").delete() }
                    doctorRepository.clearDoctorPhotoUrl(doctorId)
                }
                call.respond(MessageResponse("Аватар удалён"))
            }

            get("/documents/{documentId}/download") {
                if (!call.requireRole("admin")) return@get call.respond(HttpStatusCode.Forbidden)
                val docId = runCatching { UUID.fromString(call.parameters["documentId"]) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный documentId"))
                }
                val doc = documentRepository.findByIdAny(docId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Документ не найден"))
                val file = File(doc.filePath)
                if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Файл не найден"))
                val safeFileName = doc.fileName.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ ]"), "_").take(200)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$safeFileName\"")
                call.respondFile(file)
            }

            get("/users/{userId}/documents") {
                if (!call.requireRole("admin")) return@get call.respond(HttpStatusCode.Forbidden)
                val userId = runCatching { UUID.fromString(call.parameters["userId"]) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный userId"))
                }
                val docs = documentRepository.findByUser(userId)
                call.respond(docs.map { doc ->
                    DocumentResponse(
                        documentId  = doc.documentId.toString(),
                        fileName    = doc.fileName,
                        fileType    = doc.fileType,
                        fileSize    = doc.fileSize,
                        category    = doc.category,
                        description = doc.description,
                        createdAt   = doc.createdAt.toString(),
                        downloadUrl = "/admin/documents/${doc.documentId}/download"
                    )
                })
            }

            post("/doctors/{doctorId}/photo") {
                if (!call.requireRole("admin")) return@post call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.parameters["doctorId"]?.toIntOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный doctorId"))
                val (ext, savedFile, overLimit) = receivePhotoUpload(
                    call.receiveMultipart(), doctorPhotoDir(), doctorId.toString()
                )
                when {
                    overLimit     -> call.respond(HttpStatusCode.PayloadTooLarge, MessageResponse("Файл слишком большой (макс. 5 МБ)"))
                    ext.isEmpty() -> call.respond(HttpStatusCode.BadRequest,  MessageResponse("Поддерживаются только jpg/jpeg/png"))
                    savedFile == null -> call.respond(HttpStatusCode.BadRequest, MessageResponse("Файл не получен"))
                    else -> {
                        listOf("jpg", "png").filter { it != ext }.forEach { File("${doctorPhotoDir()}/$doctorId.$it").delete() }
                        resizeAvatar(savedFile, 512)
                        val url = "/api/v1/doctors/$doctorId/photo"
                        doctorRepository.updateDoctor(doctorId, null, null, null, null, url, null)
                        doctorRepository.findById(doctorId)?.userId?.let { linkedUserId ->
                            listOf("jpg", "png").filter { it != ext }.forEach { File("$AVATAR_DIR/$linkedUserId.$it").delete() }
                            savedFile.copyTo(File("$AVATAR_DIR/$linkedUserId.$ext"), overwrite = true)
                            userRepository.updateAvatarUrl(linkedUserId, "/api/v1/auth/photo/$linkedUserId")
                        }
                        call.respond(mapOf("photoUrl" to url))
                    }
                }
            }

            delete("/doctors/{doctorId}/photo") {
                if (!call.requireRole("admin")) return@delete call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.parameters["doctorId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный doctorId"))
                listOf("jpg", "png").forEach { File("${doctorPhotoDir()}/$doctorId.$it").delete() }
                doctorRepository.clearDoctorPhotoUrl(doctorId)
                doctorRepository.findById(doctorId)?.userId?.let { linkedUserId ->
                    listOf("jpg", "png").forEach { File("$AVATAR_DIR/$linkedUserId.$it").delete() }
                    userRepository.clearAvatarUrl(linkedUserId)
                }
                call.respond(MessageResponse("Фото удалено"))
            }

            delete("/clinics/{clinicId}/logo") {
                if (!call.requireRole("admin")) return@delete call.respond(HttpStatusCode.Forbidden)
                val clinicId = call.parameters["clinicId"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный clinicId"))
                listOf("jpg", "png").forEach { File("${clinicLogoDir()}/$clinicId.$it").delete() }
                clinicRepository.clearLogoUrl(clinicId)
                call.respond(MessageResponse("Логотип удалён"))
            }
        }
    }
}

private data class UploadResult(val ext: String, val file: File?, val overLimit: Boolean)

private suspend fun receivePhotoUpload(
    multipart: MultiPartData,
    dir: String,
    id: String
): UploadResult {
    var ext = ""; var savedFile: File? = null; var overLimit = false
    multipart.forEachPart { part ->
        if (part is PartData.FileItem && part.name == "photo") {
            val orig = part.originalFileName ?: ""
            ext = when {
                orig.endsWith(".jpg",  ignoreCase = true) -> "jpg"
                orig.endsWith(".jpeg", ignoreCase = true) -> "jpg"
                orig.endsWith(".png",  ignoreCase = true) -> "png"
                else -> ""
            }
            if (ext.isNotEmpty()) {
                val file = File("$dir/$id.$ext")
                var written = 0L
                part.streamProvider().use { inp ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(8192); var n: Int
                        while (inp.read(buf).also { n = it } != -1) {
                            written += n
                            if (written > AVATAR_MAX_BYTES) { overLimit = true; break }
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (overLimit) file.delete() else savedFile = file
            }
        }
        part.dispose()
    }
    return UploadResult(ext, savedFile, overLimit)
}

private fun com.eva.domain.models.Appointment.toAdminDto() = AdminAppointmentResponse(
    appointmentId    = appointmentId.toString(),
    patientName      = patientName,
    patientId        = userId.toString(),
    doctorName       = doctorName,
    doctorId         = doctorId,
    specializationName = specializationName,
    clinicName       = clinicName,
    slotDate         = slotDate.toString(),
    slotTime         = slotTime.toString(),
    status           = status,
    notes            = notes,
    doctorConclusion = doctorConclusion,
    createdAt        = createdAt.toString()
)
