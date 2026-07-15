import com.cs30.server.models.LoginSession
import com.cs30.server.service.ApiTokenStore
import com.cs30.server.service.StudentIdentityService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class StudentIdentityServiceTest {

    private lateinit var tokenStore: ApiTokenStore
    private lateinit var identityService: StudentIdentityService

    @BeforeEach
    fun setUp() {
        tokenStore = mockk(relaxed = true)
        identityService = StudentIdentityService(tokenStore)
    }

    @Test
    fun `resolve should return email for valid Bearer token`() {
        val session = LoginSession(
            token = "valid-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now()
        )
        every { tokenStore.activeSession("valid-token") } returns session

        val result = identityService.resolve("Bearer valid-token")

        assertEquals("student@sjsu.edu", result)
    }

    @Test
    fun `resolve should return null for missing header`() {
        val result = identityService.resolve(null)

        assertNull(result)
    }

    @Test
    fun `resolve should return null for non-Bearer header`() {
        val result = identityService.resolve("Basic dXNlcjpwYXNz")

        assertNull(result)
    }

    @Test
    fun `resolve should return null for invalid token`() {
        every { tokenStore.activeSession("invalid-token") } returns null

        val result = identityService.resolve("Bearer invalid-token")

        assertNull(result)
    }

    @Test
    fun `resolve should handle Bearer prefix with extra whitespace`() {
        val session = LoginSession(
            token = "valid-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now()
        )
        every { tokenStore.activeSession("valid-token") } returns session

        val result = identityService.resolve("Bearer   valid-token  ")

        assertEquals("student@sjsu.edu", result)
    }

    @Test
    fun `platform should return platform for valid token`() {
        every { tokenStore.platformFor("valid-token") } returns "desktop"

        val result = identityService.platform("Bearer valid-token")

        assertEquals("desktop", result)
    }

    @Test
    fun `platform should return unknown for missing header`() {
        val result = identityService.platform(null)

        assertEquals("unknown", result)
    }

    @Test
    fun `platform should return unknown for invalid token`() {
        every { tokenStore.platformFor("invalid-token") } returns null

        val result = identityService.platform("Bearer invalid-token")

        assertEquals("unknown", result)
    }

    @Test
    fun `token should extract token from valid Bearer header`() {
        val result = identityService.token("Bearer my-token-123")

        assertEquals("my-token-123", result)
    }

    @Test
    fun `token should return empty string for missing header`() {
        val result = identityService.token(null)

        assertEquals("", result)
    }

    @Test
    fun `token should return empty string for non-Bearer header`() {
        val result = identityService.token("Basic credentials")

        assertEquals("", result)
    }

    @Test
    fun `token should trim whitespace from extracted token`() {
        val result = identityService.token("Bearer   token-with-spaces   ")

        assertEquals("token-with-spaces", result)
    }
}