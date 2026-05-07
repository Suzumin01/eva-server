package com.eva.api.routes

import com.eva.api.dto.DocumentResponse
import com.eva.data.repository.DocumentRepositoryImpl
import com.eva.plugins.getUserId
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.UUID

private val UPLOAD_DIR: String = run {
    val fromEnv = System.getenv("UPLOAD_DIR")
    if (!fromEnv.isNullOrBlank()) {
        fromEnv
    } else {
        // В Docker задаётся через ENV UPLOAD_DIR=/app/uploads.
        // При локальном запуске создаём папку рядом с рабочим каталогом.
        val localDir = File("uploads").absoluteFile
        localDir.mkdirs()
        localDir.absolutePath
    }
}
private const val MAX_FILE_SIZE = 20 * 1024 * 1024L // 20 MB

fun Route.documentRoutes(documentRepository: DocumentRepositoryImpl) {
    authenticate("jwt-auth") {
        route("/documents") {

            get {
                val userId = UUID.fromString(call.getUserId())
                val docs   = documentRepository.findByUser(userId)
                call.respond(docs.map { it.toResponse() })
            }

            post {
                val userId = UUID.fromString(call.getUserId())
                val multipart = call.receiveMultipart()

                var fileName    = ""
                var fileType    = "other"
                var category    = "other"
                var description: String? = null
                var savedPath   = ""
                var fileSize    = 0L

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> {
                            when (part.name) {
                                "category"    -> category    = part.value
                                "description" -> description = part.value.ifBlank { null }
                            }
                        }
                        is PartData.FileItem -> {
                            fileName = part.originalFileName?.take(255) ?: "document"
                            fileType = when {
                                fileName.endsWith(".pdf", true)              -> "pdf"
                                fileName.matches(Regex(".*\\.(jpg|jpeg|png|heic)", RegexOption.IGNORE_CASE)) -> "image"
                                else -> "other"
                            }
                            val dir = File("$UPLOAD_DIR/$userId").also {
                                if (!it.exists() && !it.mkdirs()) {
                                    // директорию создать не удалось — прерываем загрузку
                                    savedPath = ""
                                    fileSize  = -1L   // сигнал об ошибке ФС
                                    part.dispose()
                                    return@forEachPart
                                }
                            }
                            val file = File(dir, "${UUID.randomUUID()}_${fileName.replace(":", "_")}")
                            var written = 0L
                            var overLimit = false
                            part.streamProvider().use { input ->
                                file.outputStream().use { output ->
                                    val buf = ByteArray(8192)
                                    var read: Int
                                    while (input.read(buf).also { read = it } != -1) {
                                        written += read
                                        if (written > MAX_FILE_SIZE) {
                                            overLimit = true
                                            break
                                        }
                                        output.write(buf, 0, read)
                                    }
                                }
                            }
                            if (overLimit) {
                                file.delete()
                                // savedPath остаётся "" — после цикла вернём 413
                                fileSize = written
                            } else {
                                savedPath = file.absolutePath
                                fileSize  = written
                            }
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                if (fileSize == -1L) {
                    return@post call.respond(HttpStatusCode.InternalServerError,
                        mapOf("message" to "Не удалось создать директорию для загрузки файлов. " +
                                "Проверьте переменную UPLOAD_DIR или права доступа."))
                }

                if (fileSize > MAX_FILE_SIZE) {
                    return@post call.respond(HttpStatusCode.PayloadTooLarge,
                        mapOf("message" to "Файл слишком большой (макс. 20 МБ)"))
                }

                if (savedPath.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        mapOf("message" to "Файл не получен"))
                }

                val docId = documentRepository.create(
                    userId = userId, fileName = fileName, fileType = fileType,
                    filePath = savedPath, fileSize = fileSize,
                    category = category, description = description
                )
                call.respond(HttpStatusCode.Created, mapOf("documentId" to docId.toString()))
            }

            get("/{id}/download") {
                val userId = UUID.fromString(call.getUserId())
                val docId  = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val doc = documentRepository.findById(docId, userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                val file = File(doc.filePath)
                if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
                // Санируем имя файла: оставляем только безопасные символы,
                // чтобы исключить header injection через спецсимволы и переводы строк
                val safeFileName = doc.fileName
                    .replace(Regex("[^a-zA-Z0-9._\\-а-яА-ЯёЁ ]"), "_")
                    .take(200)
                call.response.header(HttpHeaders.ContentDisposition,
                    "attachment; filename=\"$safeFileName\"")
                call.respondFile(file)
            }

            patch("/{id}") {
                val userId = UUID.fromString(call.getUserId())
                val docId  = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@patch call.respond(HttpStatusCode.BadRequest)
                val req = runCatching { call.receive<com.eva.api.dto.UpdateDocumentRequest>() }.getOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("message" to "Некорректный запрос"))
                val updated = documentRepository.update(docId, userId, req.description, req.category)
                if (updated) call.respond(mapOf("message" to "Обновлено"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "Документ не найден"))
            }

            delete("/{id}") {
                val userId = UUID.fromString(call.getUserId())
                val docId  = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                val filePath = documentRepository.delete(docId, userId)
                    ?: return@delete call.respond(HttpStatusCode.NotFound)
                File(filePath).delete()
                call.respond(mapOf("message" to "Документ удалён"))
            }
        }
    }
}

private fun DocumentRepositoryImpl.Document.toResponse() = DocumentResponse(
    documentId  = documentId.toString(),
    fileName    = fileName,
    fileType    = fileType,
    fileSize    = fileSize,
    category    = category,
    description = description,
    createdAt   = createdAt.toString(),
    downloadUrl = "/api/v1/documents/$documentId/download"
)