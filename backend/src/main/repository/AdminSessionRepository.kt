package com.cs30.server.repository

import com.cs30.server.models.AdminSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface AdminSessionRepository : JpaRepository<AdminSession, String> {
    fun findByLastHeartbeatAtBefore(cutoff: LocalDateTime): List<AdminSession>
}
