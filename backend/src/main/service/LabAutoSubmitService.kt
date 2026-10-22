package com.cs30.server.service

import com.cs30.server.dto.SubmitCodeRequest
import com.cs30.server.models.Course
import com.cs30.server.models.ScheduledLab
import com.cs30.server.repository.CourseRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-submits each student's last autosave once their lab has ended (issue #262).
 *
 * One server-wide job, not one per student: a pass with no newly ended lab is a single course read
 * plus a time comparison per lab. Per-student work happens once per lab, right after it ends, and
 * students are judged one at a time so a closing lab never floods the judge queue.
 *
 * A student is submitted only when their autosave differs from their newest submission, so a
 * restart (which empties [doneLabs]) or a repeated pass never submits the same code twice.
 */
@Service
class LabAutoSubmitService(
    private val courseRepository: CourseRepository,
    private val codeService: CodeService,
    @Value("\${cs30.auto-submit.lookback-hours:24}")
    private val lookbackHours: Long,
) {
    private val log = LoggerFactory.getLogger(LabAutoSubmitService::class.java)

    // ScheduledLab ids already handled since this server started.
    private val doneLabs = ConcurrentHashMap.newKeySet<String>()

    @Scheduled(fixedDelayString = "\${cs30.auto-submit.interval-ms:60000}")
    fun submitEndedLabs() {
        val oldestEnd = LocalDateTime.now(ZoneOffset.UTC).minusHours(lookbackHours)
        // With students: course.students is lazy and this runs outside any persistence session.
        for (course in courseRepository.findAllWithStudents()) {
            if (course.studentGitRepo.isBlank()) continue
            course.labs
                .filter { it.isPast && it.endDateTime > oldestEnd && it.id !in doneLabs }
                .forEach { lab ->
                    submitLab(course, lab)
                    doneLabs.add(lab.id)
                }
        }
    }

    private fun submitLab(course: Course, lab: ScheduledLab) {
        val startMs = System.currentTimeMillis()
        var submitted = 0
        var skipped = 0
        var failed = 0
        for (email in course.students) {
            for (problem in lab.problems) {
                val code = readAutosave(course, lab.labNumber, problem.name, email)
                if (code.isNullOrBlank() || code == latestSubmittedCode(course, lab.labNumber, problem.name, email)) {
                    skipped++
                    continue
                }
                val response = codeService.autoSubmit(
                    course,
                    SubmitCodeRequest(
                        courseId = course.id,
                        section = course.section,
                        labNumber = lab.labNumber,
                        problemName = problem.name,
                        studentEmail = email,
                        code = code,
                        language = problem.language.ifBlank { course.language },
                    ),
                )
                if (response.success) submitted++ else failed++
            }
        }
        log.info(
            "[AUTO-SUBMIT] course={} section={} lab={} submitted={} skipped={} failed={} tookMs={}",
            course.code, course.section, lab.labNumber, submitted, skipped, failed,
            System.currentTimeMillis() - startMs,
        )
    }

    /** The student's autosaved code for a problem, or null if they never autosaved it. */
    private fun readAutosave(course: Course, labNumber: Int, problemName: String, email: String): String? =
        File(course.studentGitRepo, "section_${course.section}/lab_$labNumber/$problemName/$email")
            .listFiles { file -> file.isFile && file.name.startsWith(AUTOSAVE_FILE_PREFIX) }
            ?.maxByOrNull { it.lastModified() }
            ?.readText()

    private fun latestSubmittedCode(course: Course, labNumber: Int, problemName: String, email: String): String? =
        codeService.listSubmissions(course.id, course.section, labNumber, problemName, email)
            .firstOrNull()?.code

    companion object {
        // GitService.saveAutosolution writes autosaved-solution.<ext>; the extension depends on the language.
        private const val AUTOSAVE_FILE_PREFIX = "autosaved-solution."
    }
}
