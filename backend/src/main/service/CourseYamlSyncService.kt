package com.cs30.server.service

import com.cs30.server.models.Course
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/** The course whose file needs rewriting. A data class so a set dedupes requests for free. */
data class CourseYamlTarget(
    val code: String,
    val year: Int,
    val semester: String,
    /**
     * Carried in the payload rather than looked up later: removeCourse deletes the row, so by the
     * time the file is written there may be nothing left to read the path from.
     */
    val studentGitRepo: String,
) {
    constructor(course: Course) : this(course.code, course.year, course.semester, course.studentGitRepo)
}

/**
 * Keeps `<studentGitRepo>/course.yml` matching the database.
 *
 * Every service method that changes course, roster, lab or problem data calls [requestSync]; the
 * file is then regenerated and committed once the transaction commits. Without this the file drifts
 * behind the database, and the next `addcourse --course-file` re-import silently deletes whatever
 * was added in the meantime - which is exactly what issue #252 was.
 *
 * Writing after commit, not during, is the point: a rolled-back transaction must not leave a file
 * claiming a roster that was never saved. One file covers every section of a course, so requests
 * are deduplicated per transaction and a multi-section command produces a single commit.
 *
 * Known limitation: if a course's studentGitRepo is changed, the file already written to the old
 * repo stays behind and goes stale. Moving a repo is a manual, once-a-semester operation.
 */
@Service
open class CourseYamlSyncService(
    private val courseYamlService: CourseYamlService,
    private val gitService: GitService,
    private val settings: CourseYamlSyncSettings,
) {
    private val log = LoggerFactory.getLogger(CourseYamlSyncService::class.java)

    private val pending = ThreadLocal<LinkedHashSet<CourseYamlTarget>>()

    fun requestSync(course: Course) = requestSync(CourseYamlTarget(course))

    fun requestSync(target: CourseYamlTarget) {
        if (!settings.enabled) return

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to wait on (a caller outside the service layer). Write now rather than
            // drop the request - a silently skipped sync is the failure this class exists to prevent.
            syncQuietly(target)
            return
        }

        val batch = pending.get() ?: LinkedHashSet<CourseYamlTarget>().also {
            pending.set(it)
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                // afterCompletion, not afterCommit, so the ThreadLocal is cleared on rollback too:
                // a leaked one would follow this thread into the next request it serves.
                override fun afterCompletion(status: Int) {
                    val targets = pending.get().orEmpty()
                    pending.remove()
                    if (status == TransactionSynchronization.STATUS_COMMITTED) {
                        targets.forEach { syncQuietly(it) }
                    }
                }
            })
        }
        batch.add(target)
    }

    /**
     * Never throws. An exception here would surface from the transaction interceptor to the caller,
     * so a git hiccup would fail a TA's add-student request for a student who was, in fact, added.
     * The database change stands; the stale file is reported instead.
     */
    /**
     * Makes sure the course's file matches the database, for the lab health check. Catches drift no
     * requestSync can see - a psql edit, a hand edit of the file, a swallowed git failure - by
     * regenerating when the file differs. Null when there is nothing to report; never throws.
     */
    fun ensureCurrent(course: Course): String? {
        val target = CourseYamlTarget(course)
        if (!settings.enabled || target.studentGitRepo.isBlank() || !gitService.repositoryExists(target.studentGitRepo)) {
            return null
        }
        return try {
            val expected = courseYamlService.render(courseYamlService.findSections(target.code, target.year, target.semester))
            val file = java.io.File(target.studentGitRepo, settings.fileName)
            if (file.isFile && file.readText() == expected) return null
            sync(target)
            "${settings.fileName} was stale - regenerated and committed"
        } catch (e: Exception) {
            log.error("[course-yaml-sync] health check could not regenerate {} in {}", settings.fileName, target.studentGitRepo, e)
            "${settings.fileName} is stale and could not be regenerated: ${e.message}"
        }
    }

    private fun syncQuietly(target: CourseYamlTarget) {
        try {
            sync(target)
        } catch (e: Throwable) {
            log.error(
                "[course-yaml-sync] could not write {} for {} {} {} to {} - the database change WAS " +
                    "applied, so the file is now stale; re-run `cs30 exportcourse` to regenerate it",
                settings.fileName, target.code, target.semester, target.year, target.studentGitRepo, e
            )
        }
    }

    /**
     * Not annotated: this is reached by self-invocation, which bypasses the proxy, so a
     * @Transactional here would do nothing. CourseYamlService.findSections carries the
     * REQUIRES_NEW that this phase needs.
     */
    open fun sync(target: CourseYamlTarget) {
        if (target.studentGitRepo.isBlank()) {
            log.warn(
                "[course-yaml-sync] {} {} {} has no studentGitRepo, so {} cannot be kept up to date",
                target.code, target.semester, target.year, settings.fileName
            )
            return
        }
        if (!gitService.repositoryExists(target.studentGitRepo)) {
            log.warn(
                "[course-yaml-sync] no git repository at {}, skipping {} for {} {} {}",
                target.studentGitRepo, settings.fileName, target.code, target.semester, target.year
            )
            return
        }

        val sections = courseYamlService.findSections(target.code, target.year, target.semester)
        if (sections.isEmpty()) {
            // Every section was deleted. Leaving the file behind would let someone re-create the
            // course from a roster nobody is maintaining any more.
            if (settings.removeOnEmpty) {
                gitService.removeFileFromRepo(
                    target.studentGitRepo, settings.fileName,
                    "${settings.commitMessage} (${target.code} ${target.semester} ${target.year} removed)"
                )
                log.info(
                    "[course-yaml-sync] removed {} from {} ({} {} {} no longer has any sections)",
                    settings.fileName, target.studentGitRepo, target.code, target.semester, target.year
                )
            }
            return
        }

        gitService.saveTextToRepo(
            target.studentGitRepo,
            settings.fileName,
            courseYamlService.render(sections),
            settings.commitMessage
        )
        log.info(
            "[course-yaml-sync] wrote {} to {} for {} {} {} ({} sections)",
            settings.fileName, target.studentGitRepo, target.code, target.semester, target.year, sections.size
        )
    }
}
