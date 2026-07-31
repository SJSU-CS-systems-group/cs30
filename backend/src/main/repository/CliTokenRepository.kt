package com.cs30.server.repository

import com.cs30.server.models.CliToken
import com.cs30.server.models.CliTokenRole
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CliTokenRepository : JpaRepository<CliToken, String> {
    fun findFirstByRole(role: CliTokenRole): CliToken?
}
