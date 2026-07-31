package com.cs30.server.repository

import com.cs30.server.models.AdminSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AdminSessionRepository : JpaRepository<AdminSession, String>
