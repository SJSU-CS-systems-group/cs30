package com.cs30.server.controller

import com.cs30.server.dto.LabHealthReport
import com.cs30.server.service.LabHealthService
import com.cs30.server.service.TaAccess
import com.cs30.server.service.TaIdentityService
import com.cs30.server.service.denied
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Pre-lab readiness check. Grades an accepted solution for every problem in a lab and reports what,
 * if anything, is missing. Intended to be run before a lab opens.
 *
 * Gated the same way as every other TA endpoint: the caller must be the TA assigned to the course
 * being checked (there's no separate admin/instructor role in this app — the TA on a course is the
 * only elevated identity), since grading every problem in a lab is expensive.
 */
@RestController
@RequestMapping("/api/admin")
class LabHealthController(
    private val labHealthService: LabHealthService,
    private val taIdentityService: TaIdentityService,
) {
    @GetMapping("/lab-health")
    fun labHealth(
        @RequestParam courseId: String,
        @RequestParam labNumber: Int,
        @RequestHeader("Authorization", required = false) authHeader: String?,
    ): ResponseEntity<LabHealthReport> {
        val access = taIdentityService.authorize(authHeader)
        if (access !is TaAccess.Granted) return access.denied()
        if (access.courses.none { it.id == courseId }) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        val report = labHealthService.checkLab(courseId, labNumber)
        return ResponseEntity.ok(report)
    }
}
