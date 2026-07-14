package com.cs30.server.repository

import com.cs30.server.models.LoginSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface LoginSessionRepository : JpaRepository<LoginSession, String> {
    /** Full login/logout history for a student — not used internally, kept for future reporting. */
    fun findByStudentEmail(email: String): List<LoginSession>

    fun findFirstByStudentEmailAndLoggedOutAtIsNull(email: String): LoginSession?

    fun existsByStudentEmailAndLoggedOutAtIsNull(email: String): Boolean

    fun findByLoggedOutAtIsNullAndLastHeartbeatAtBefore(cutoff: LocalDateTime): List<LoginSession>

    /** Active sessions for students in a given list of emails (for TA dashboard). */
    fun findByStudentEmailInAndLoggedOutAtIsNull(emails: Collection<String>): List<LoginSession>

    /** All active sessions (for TA dashboard when viewing all sections). */
    fun findByLoggedOutAtIsNull(): List<LoginSession>

    /** All sessions (active and logged out) for students in a list, ordered by login time descending. */
    fun findByStudentEmailInOrderByLoggedInAtDesc(emails: Collection<String>): List<LoginSession>
}
