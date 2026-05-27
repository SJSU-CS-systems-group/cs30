package com.cs30.server.controller

import org.springframework.core.io.Resource
import org.springframework.core.io.UrlResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@RestController
@RequestMapping("/api/files")
class FileController {

    private val storageDir: Path = Paths.get("downloads")
    private val restTemplate = RestTemplate()

    init {
        Files.createDirectories(storageDir)
    }

    @PostMapping("/store")
    fun storeFile(@RequestParam("file") file: MultipartFile): ResponseEntity<Map<String, String>> {
        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "File is empty"))
        }
        val filename = file.originalFilename ?: "file"
        val targetPath = storageDir.resolve(filename)
        file.inputStream.use { input ->
            Files.copy(input, targetPath)
        }
        return ResponseEntity.ok(mapOf("filename" to filename))
    }

    @PostMapping("/download-url")
    fun downloadFromUrl(
        @RequestParam url: String,
        @RequestParam(required = false) filename: String?
    ): ResponseEntity<Map<String, String>> {
        val bytes = restTemplate.getForObject(url, ByteArray::class.java)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Failed to download"))

        val targetName = filename ?: url.substringAfterLast("/").ifEmpty { "download" }
        val targetPath = storageDir.resolve(targetName)
        Files.write(targetPath, bytes)

        return ResponseEntity.ok(mapOf("filename" to targetName, "size" to bytes.size.toString()))
    }

    @PostMapping("/upload")
    fun uploadFile(@RequestParam("file") file: MultipartFile): ResponseEntity<Map<String, String>> {
        if (file.isEmpty) {
            return ResponseEntity.badRequest().body(mapOf("error" to "File is empty"))
        }

        val filename = file.originalFilename ?: "upload"
        val targetPath = storageDir.resolve(filename)
        file.inputStream.use { input ->
            Files.copy(input, targetPath)
        }

        return ResponseEntity.ok(mapOf("filename" to filename, "size" to file.size.toString()))
    }

    @PostMapping("/save-text")
    fun saveText(
        @RequestParam filename: String,
        @RequestBody content: String
    ): ResponseEntity<Map<String, String>> {
        val targetPath = storageDir.resolve(filename)
        Files.writeString(targetPath, content)

        return ResponseEntity.ok(mapOf("filename" to filename))
    }

    @GetMapping("/{filename}")
    fun getFile(@PathVariable filename: String): ResponseEntity<Resource> {
        val filePath = storageDir.resolve(filename)
        if (!Files.exists(filePath)) {
            return ResponseEntity.notFound().build()
        }

        val resource = UrlResource(filePath.toUri())
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(resource)
    }
}