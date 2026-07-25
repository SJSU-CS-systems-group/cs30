package com.cs30.server.controller

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

// Verifies the deploy health gate contract: /health returns 200 with status=ok
@WebMvcTest
@ContextConfiguration(classes = [HealthController::class])
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {

    @Autowired lateinit var mvc: MockMvc

    @Test fun `health is 200 ok`() {
        mvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ok") }
        }
    }
}
