package com.cs30.server.repository

import com.cs30.server.models.LoginSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface LoginSessionRepository : JpaRepository<LoginSession, String> {
    fun findByStudentEmail(email: String): List<LoginSession>
}
