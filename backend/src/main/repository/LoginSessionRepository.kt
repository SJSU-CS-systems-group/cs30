package com.cs30.server.repository

import com.cs30.server.models.LoginSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface LoginSessionRepository : JpaRepository<LoginSession, String> {
    /** Full login/logout history for a student — not used internally, kept for future reporting. */
    fun findByStudentEmail(email: String): List<LoginSession>

    fun findByStudentEmailAndLoggedOutAtIsNull(email: String): LoginSession?

    fun existsByStudentEmailAndLoggedOutAtIsNull(email: String): Boolean

    fun findByLoggedOutAtIsNullAndLastHeartbeatAtBefore(cutoff: LocalDateTime): List<LoginSession>

    @Modifying
    @Query("DELETE FROM LoginSession s WHERE s.studentEmail IN :emails")
    fun deleteAllByStudentEmailIn(@Param("emails") emails: Collection<String>)
}
