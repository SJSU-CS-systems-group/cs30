package com.cs30.server.repository

import com.cs30.server.models.TaSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface TaSessionRepository : JpaRepository<TaSession, String> {
    fun findByEmail(email: String): List<TaSession>
}
