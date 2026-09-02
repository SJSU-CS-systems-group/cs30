package com.cs30.server.repository

import com.cs30.server.models.StudentOverride
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface StudentOverrideRepository : JpaRepository<StudentOverride, String>
