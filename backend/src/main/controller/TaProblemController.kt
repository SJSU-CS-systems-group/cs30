package com.cs30.server.controller

import com.cs30.server.repository.CourseRepository
import com.cs30.server.service.CliTokenService
import com.cs30.server.service.GitService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.util.zip.ZipInputStream

@RestController
@RequestMapping("/api/ta")
class TaProblemController(
    private val cliTokenService: CliTokenService,
    private val courseRepository: CourseRepository,
    private val gitService: GitService,
) {
    private val log = LoggerFactory.getLogger(TaProblemController::class.java)

    @PostMapping("/problems/upload", consumes = ["multipart/form-data"])
    fun uploadProblem(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("courseCode") courseCode: String,
        @RequestParam("year") year: Int,
        @RequestParam("semester") semester: String,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<Map<String, Any>> {
        val token = authHeader?.removePrefix("Bearer ")?.trim().orEmpty()
        val resolved = cliTokenService.resolveToken(token)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Valid CLI token required"))

        val courses = courseRepository.findByCodeAndYearAndSemester(courseCode, year, semester)
        if (courses.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Course not found: $courseCode $semester $year"))
        }

        val course = courses.firstOrNull { it.problemGitRepo.isNotBlank() }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Course has no problem git repo configured"))

        if (file.isEmpty) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Uploaded file is empty"))
        }

        val tempDir = File.createTempFile("problem-upload", "").apply { delete(); mkdirs() }
        try {
            val problemName = extractZip(file, tempDir)
                ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "ZIP must contain exactly one top-level problem directory"))

            log.info("[problem-upload] {} uploading problem '{}' for course {} {} {}",
                resolved.email, problemName, courseCode, semester, year)
            gitService.addProblemToRepo(course.problemGitRepo, File(tempDir, problemName).absolutePath)
            log.info("[problem-upload] Problem '{}' added successfully", problemName)

            return ResponseEntity.ok(mapOf(
                "success" to true,
                "problemName" to problemName,
            ))
        } catch (e: SecurityException) {
            log.warn("[problem-upload] Path traversal attempt in ZIP from {}", resolved.email)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid ZIP: ${e.message}"))
        } catch (e: java.util.zip.ZipException) {
            log.warn("[problem-upload] Invalid ZIP from {}: {}", resolved.email, e.message)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid or corrupt ZIP file: ${e.message}"))
        } catch (e: Exception) {
            log.error("[problem-upload] Failed to add problem for course {} {} {}",
                courseCode, semester, year, e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to (e.message ?: "Failed to add problem")))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @PostMapping("/problems/upload-batch", consumes = ["multipart/form-data"])
    fun uploadBatch(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("courseCode") courseCode: String,
        @RequestParam("year") year: Int,
        @RequestParam("semester") semester: String,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<Map<String, Any>> {
        val token = authHeader?.removePrefix("Bearer ")?.trim().orEmpty()
        val resolved = cliTokenService.resolveToken(token)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(mapOf("error" to "Valid CLI token required"))

        val courses = courseRepository.findByCodeAndYearAndSemester(courseCode, year, semester)
        if (courses.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("error" to "Course not found: $courseCode $semester $year"))
        }

        val course = courses.firstOrNull { it.problemGitRepo.isNotBlank() }
            ?: return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Course has no problem git repo configured"))

        if (file.isEmpty) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Uploaded file is empty"))
        }

        val tempDir = File.createTempFile("problems-upload", "").apply { delete(); mkdirs() }
        try {
            val problemNames = extractZipBatch(file, tempDir)
            if (problemNames.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(mapOf("error" to "ZIP must contain at least one top-level problem directory"))
            }

            log.info("[problems-upload] {} uploading {} problem(s) for course {} {} {}",
                resolved.email, problemNames.size, courseCode, semester, year)
            gitService.addProblemsToRepo(course.problemGitRepo, tempDir.absolutePath)
            log.info("[problems-upload] {} problem(s) added successfully: {}", problemNames.size, problemNames)

            return ResponseEntity.ok(mapOf(
                "success" to true,
                "problemsAdded" to problemNames.size,
                "problemNames" to problemNames,
            ))
        } catch (e: SecurityException) {
            log.warn("[problems-upload] Path traversal attempt in ZIP from {}", resolved.email)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid ZIP: ${e.message}"))
        } catch (e: java.util.zip.ZipException) {
            log.warn("[problems-upload] Invalid ZIP from {}: {}", resolved.email, e.message)
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(mapOf("error" to "Invalid or corrupt ZIP file: ${e.message}"))
        } catch (e: Exception) {
            log.error("[problems-upload] Failed to add problems for course {} {} {}",
                courseCode, semester, year, e)
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(mapOf("error" to (e.message ?: "Failed to add problems")))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun extractZip(file: MultipartFile, destDir: File): String? {
        ZipInputStream(file.inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw SecurityException("Path traversal attempt: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        // Filter out macOS metadata directories (__MACOSX) that may appear in zips created on macOS.
        val topLevel = destDir.listFiles()?.filter { it.isDirectory && it.name != "__MACOSX" } ?: emptyList()
        return if (topLevel.size == 1) topLevel.first().name else null
    }

    private fun extractZipBatch(file: MultipartFile, destDir: File): List<String> {
        ZipInputStream(file.inputStream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                    throw SecurityException("Path traversal attempt: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return destDir.listFiles()?.filter { it.isDirectory && it.name != "__MACOSX" }?.map { it.name } ?: emptyList()
    }
}
