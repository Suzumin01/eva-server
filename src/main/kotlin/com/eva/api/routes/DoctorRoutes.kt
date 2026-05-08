package com.eva.api.routes

import com.eva.api.dto.*
import com.eva.data.repository.AppointmentRepositoryImpl
import com.eva.data.repository.DocumentRepositoryImpl
import com.eva.data.repository.ScheduleRepositoryImpl
import com.eva.plugins.getDoctorId
import com.eva.plugins.requireRole
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

fun Route.doctorDashboardRoutes(
    appointmentRepository: AppointmentRepositoryImpl,
    scheduleRepository: ScheduleRepositoryImpl,
    documentRepository: DocumentRepositoryImpl
) {
    authenticate("jwt-auth") {
        route("/doctor") {

            get("/appointments") {
                if (!call.requireRole("doctor"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val status   = call.request.queryParameters["status"]
                val dateFrom = call.request.queryParameters["dateFrom"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val dateTo   = call.request.queryParameters["dateTo"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val list     = appointmentRepository.findByDoctor(doctorId, status, dateFrom, dateTo)
                call.respond(list.map { it.toDoctorDto() })
            }

            get("/appointments/{id}") {
                if (!call.requireRole("doctor"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val appointmentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                }
                val appt = appointmentRepository.findByIdForDoctor(appointmentId, doctorId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(appt.toDoctorDto())
            }

            get("/appointments/{id}/documents") {
                if (!call.requireRole("doctor"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val appointmentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                }
                val appt = appointmentRepository.findByIdForDoctor(appointmentId, doctorId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Запись не найдена или доступ запрещён"))
                val docs = documentRepository.findByUser(appt.userId)
                call.respond(docs.map { it.toDocumentResponse() })
            }

            get("/documents/{documentId}/download") {
                if (!call.requireRole("doctor"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val docId = runCatching { UUID.fromString(call.parameters["documentId"]) }.getOrElse {
                    return@get call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный documentId"))
                }
                val doc = documentRepository.findByIdAny(docId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Документ не найден"))
                val hasAccess = appointmentRepository.doctorHasPatient(doctorId, doc.userId)
                if (!hasAccess)
                    return@get call.respond(HttpStatusCode.Forbidden, MessageResponse("Нет доступа к документам этого пациента"))
                val file = File(doc.filePath)
                if (!file.exists())
                    return@get call.respond(HttpStatusCode.NotFound, MessageResponse("Файл не найден"))
                val safeFileName = doc.fileName.replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ ]"), "_").take(200)
                call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$safeFileName\"")
                call.respondFile(file)
            }

            patch("/appointments/{id}/status") {
                if (!call.requireRole("doctor"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val appointmentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                }
                val req = call.receive<UpdateStatusRequest>()
                if (req.status !in listOf("completed", "cancelled"))
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Врач может устанавливать только: completed, cancelled"))
                val updated = appointmentRepository.updateStatus(appointmentId, doctorId, req.status)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Запись не найдена или доступ запрещён"))
                call.respond(MessageResponse("Статус обновлён: ${req.status}"))
            }

            patch("/appointments/{id}/conclusion") {
                if (!call.requireRole("doctor", "admin"))
                    return@patch call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val appointmentId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrElse {
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный id"))
                }
                val req = call.receive<SetConclusionExtRequest>()
                if (req.conclusion.isNullOrBlank() && req.notes.isNullOrBlank())
                    return@patch call.respond(HttpStatusCode.BadRequest, MessageResponse("Укажите conclusion или notes"))
                val updated = appointmentRepository.setNotesAndConclusion(appointmentId, doctorId, req.notes, req.conclusion)
                if (!updated) return@patch call.respond(HttpStatusCode.NotFound, MessageResponse("Запись не найдена или доступ запрещён"))
                call.respond(MessageResponse("Заключение сохранено"))
            }

            get("/schedules") {
                if (!call.requireRole("doctor"))
                    return@get call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val dateFrom = call.request.queryParameters["dateFrom"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val dateTo   = call.request.queryParameters["dateTo"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val slots    = scheduleRepository.findAllByDoctor(doctorId, dateFrom, dateTo)
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

            post("/schedules") {
                if (!call.requireRole("doctor"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val req = call.receive<CreateScheduleRequest>()
                val slotDate = runCatching { LocalDate.parse(req.slotDate) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректная дата"))
                }
                val slotTime = runCatching { LocalTime.parse(req.slotTime) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректное время (HH:mm)"))
                }
                val scheduleId = runCatching {
                    scheduleRepository.create(doctorId, slotDate, slotTime, req.durationMinutes.toShort())
                }.getOrElse {
                    return@post call.respond(HttpStatusCode.Conflict, MessageResponse("Слот уже существует"))
                }
                call.respond(HttpStatusCode.Created, mapOf("scheduleId" to scheduleId, "message" to "Слот создан"))
            }

            post("/schedules/bulk") {
                if (!call.requireRole("doctor"))
                    return@post call.respond(HttpStatusCode.Forbidden)
                val doctorId = call.getDoctorId()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, MessageResponse("doctorId не найден в токене"))
                val req = call.receive<BulkCreateScheduleRequest>()
                val dateFrom = runCatching { LocalDate.parse(req.dateFrom) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный dateFrom"))
                }
                val dateTo = runCatching { LocalDate.parse(req.dateTo) }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный dateTo"))
                }
                if (dateTo < dateFrom) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("dateTo не может быть раньше dateFrom"))
                val times = req.times.mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
                if (times.isEmpty()) return@post call.respond(HttpStatusCode.BadRequest, MessageResponse("Нет корректных времён"))

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
                if (!call.requireRole("doctor", "admin"))
                    return@delete call.respond(HttpStatusCode.Forbidden)
                val scheduleId = call.parameters["scheduleId"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, MessageResponse("Некорректный scheduleId"))
                val deleted = scheduleRepository.delete(scheduleId)
                if (!deleted) return@delete call.respond(HttpStatusCode.Conflict, MessageResponse("Слот не найден или уже занят"))
                call.respond(MessageResponse("Слот удалён"))
            }
        }
    }
}

private fun com.eva.domain.models.Appointment.toDoctorDto() = DoctorAppointmentResponse(
    appointmentId          = appointmentId.toString(),
    patientName            = patientName,
    patientId              = userId.toString(),
    slotDate               = slotDate.toString(),
    slotTime               = slotTime.toString(),
    durationMinutes        = durationMinutes.toInt(),
    status                 = status,
    notes                  = notes,
    doctorConclusion       = doctorConclusion,
    patientHealthInfo      = patientHealthInfo,
    patientDateOfBirth     = patientDateOfBirth?.toString(),
    patientAllergies       = patientAllergies,
    patientChronicDiseases = patientChronicDiseases,
    patientAvatarUrl       = patientAvatarUrl,
    createdAt              = createdAt.toString()
)

private fun DocumentRepositoryImpl.Document.toDocumentResponse() = DocumentResponse(
    documentId  = documentId.toString(),
    fileName    = fileName,
    fileType    = fileType,
    fileSize    = fileSize,
    category    = category,
    description = description,
    createdAt   = createdAt.toString(),
    downloadUrl = "/doctor/documents/$documentId/download"
)
