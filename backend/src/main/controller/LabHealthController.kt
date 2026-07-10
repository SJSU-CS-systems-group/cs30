package com.cs30.server.controller

import com.cs30.server.dto.LabHealthReport
import com.cs30.server.service.LabHealthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Pre-lab readiness check. Grades an accepted solution for every problem in a lab and reports what,
 * if anything, is missing. Intended to be run before a lab opens.
 *
 * NOTE: not behind auth yet — gate before exposing publicly (grading is expensive).
 */
@RestController
@RequestMapping("/api/admin")
class LabHealthController(
    private val labHealthService: LabHealthService,
) {
    @GetMapping("/lab-health")
    fun labHealth(
        @RequestParam courseId: String,
        @RequestParam labNumber: Int,
    ): ResponseEntity<LabHealthReport> {
        val report = labHealthService.checkLab(courseId, labNumber)
        return ResponseEntity.ok(report)
    }
}
