package com.cs30.server.service

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class ApiTokenStore {
    private val tokenToEmail = ConcurrentHashMap<String, String>()

    fun generate(email: String): String {
        val token = UUID.randomUUID().toString()
        tokenToEmail[token] = email
        return token
    }

    fun resolve(token: String): String? = tokenToEmail[token]

    fun revokeByEmail(email: String) {
        tokenToEmail.entries.removeIf { it.value == email }
    }
}
