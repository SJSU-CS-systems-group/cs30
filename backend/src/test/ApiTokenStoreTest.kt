import com.cs30.server.models.LoginSession
import com.cs30.server.repository.LoginSessionRepository
import com.cs30.server.service.ApiTokenStore
import com.cs30.server.service.LogoutEvent
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional

class ApiTokenStoreTest {

    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var loginSessionRepository: LoginSessionRepository
    private lateinit var tokenStore: ApiTokenStore

    @BeforeEach
    fun setUp() {
        eventPublisher = mockk(relaxed = true)
        loginSessionRepository = mockk(relaxed = true)
        tokenStore = ApiTokenStore(eventPublisher, loginSessionRepository)
    }

    @Test
    fun `generate should create new session and return token`() {
        every { loginSessionRepository.save(any()) } answers { firstArg() }

        val token = tokenStore.generate("student@sjsu.edu", "web", "192.168.1.1")

        assertNotNull(token)
        assertTrue(token.isNotBlank())
        verify {
            loginSessionRepository.save(match { session ->
                session.studentEmail == "student@sjsu.edu" &&
                session.platform == "web" &&
                session.ipAddress == "192.168.1.1" &&
                session.token == token
            })
        }
    }

    @Test
    fun `hasActiveSession should return true when active session exists`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findFirstByStudentEmailAndLoggedOutAtIsNull("student@sjsu.edu") } returns session

        val result = tokenStore.hasActiveSession("student@sjsu.edu")

        assertTrue(result)
    }

    @Test
    fun `hasActiveSession should return false when no session exists`() {
        every { loginSessionRepository.findFirstByStudentEmailAndLoggedOutAtIsNull("student@sjsu.edu") } returns null

        val result = tokenStore.hasActiveSession("student@sjsu.edu")

        assertFalse(result)
    }

    @Test
    fun `hasActiveSession should return false when session is expired`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5) // Expired (TTL is 2 min)
        )
        every { loginSessionRepository.findFirstByStudentEmailAndLoggedOutAtIsNull("student@sjsu.edu") } returns session

        val result = tokenStore.hasActiveSession("student@sjsu.edu")

        assertFalse(result)
    }

    @Test
    fun `resolve should return email for active session`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)

        val result = tokenStore.resolve("test-token")

        assertEquals("student@sjsu.edu", result)
    }

    @Test
    fun `resolve should return null for non-existent token`() {
        every { loginSessionRepository.findById("invalid-token") } returns Optional.empty()

        val result = tokenStore.resolve("invalid-token")

        assertNull(result)
    }

    @Test
    fun `resolve should return null for logged out session`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            loggedOutAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)

        val result = tokenStore.resolve("test-token")

        assertNull(result)
    }

    @Test
    fun `resolve should return null for expired session`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)

        val result = tokenStore.resolve("test-token")

        assertNull(result)
    }

    @Test
    fun `platformFor should return platform for active session`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "desktop",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)

        val result = tokenStore.platformFor("test-token")

        assertEquals("desktop", result)
    }

    @Test
    fun `revokeByToken should end session and publish logout event`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)
        every { loginSessionRepository.save(any()) } answers { firstArg() }

        tokenStore.revokeByToken("test-token")

        verify { eventPublisher.publishEvent(any<LogoutEvent>()) }
        verify {
            loginSessionRepository.save(match { it.loggedOutAt != null })
        }
    }

    @Test
    fun `revokeByToken should do nothing for non-existent token`() {
        every { loginSessionRepository.findById("invalid-token") } returns Optional.empty()

        tokenStore.revokeByToken("invalid-token")

        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        verify(exactly = 0) { loginSessionRepository.save(any()) }
    }

    @Test
    fun `refreshSession should update lastHeartbeatAt for active session`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(30)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)
        every { loginSessionRepository.save(any()) } answers { firstArg() }

        val result = tokenStore.refreshSession("test-token")

        assertTrue(result)
        verify {
            loginSessionRepository.save(match { it.lastHeartbeatAt.isAfter(session.lastHeartbeatAt) })
        }
    }

    @Test
    fun `refreshSession should return false for non-existent token`() {
        every { loginSessionRepository.findById("invalid-token") } returns Optional.empty()

        val result = tokenStore.refreshSession("invalid-token")

        assertFalse(result)
    }

    @Test
    fun `refreshSession should return false and end session when expired`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)
        every { loginSessionRepository.save(any()) } answers { firstArg() }

        val result = tokenStore.refreshSession("test-token")

        assertFalse(result)
        verify { eventPublisher.publishEvent(any<LogoutEvent>()) }
        verify {
            loginSessionRepository.save(match { it.loggedOutAt != null })
        }
    }

    @Test
    fun `refreshSession should return false for already logged out session`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            loggedOutAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)

        val result = tokenStore.refreshSession("test-token")

        assertFalse(result)
        verify(exactly = 0) { loginSessionRepository.save(any()) }
    }

    @Test
    fun `cleanupExpiredSessions should end all expired sessions`() {
        val expiredSession1 = LoginSession(
            token = "token1",
            studentEmail = "student1@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)
        )
        val expiredSession2 = LoginSession(
            token = "token2",
            studentEmail = "student2@sjsu.edu",
            ipAddress = "192.168.1.2",
            platform = "desktop",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10)
        )
        every { loginSessionRepository.findByLoggedOutAtIsNullAndLastHeartbeatAtBefore(any()) } returns
            listOf(expiredSession1, expiredSession2)
        every { loginSessionRepository.save(any()) } answers { firstArg() }

        tokenStore.cleanupExpiredSessions()

        verify(exactly = 2) { eventPublisher.publishEvent(any<LogoutEvent>()) }
        verify(exactly = 2) { loginSessionRepository.save(match { it.loggedOutAt != null }) }
    }

    @Test
    fun `cleanupExpiredSessions should handle empty list`() {
        every { loginSessionRepository.findByLoggedOutAtIsNullAndLastHeartbeatAtBefore(any()) } returns emptyList()

        tokenStore.cleanupExpiredSessions()

        verify(exactly = 0) { eventPublisher.publishEvent(any()) }
        verify(exactly = 0) { loginSessionRepository.save(any()) }
    }

    @Test
    fun `activeSession should return session for valid active token`() {
        val session = LoginSession(
            token = "test-token",
            studentEmail = "student@sjsu.edu",
            ipAddress = "192.168.1.1",
            platform = "web",
            lastHeartbeatAt = LocalDateTime.now(ZoneOffset.UTC)
        )
        every { loginSessionRepository.findById("test-token") } returns Optional.of(session)

        val result = tokenStore.activeSession("test-token")

        assertNotNull(result)
        assertEquals("student@sjsu.edu", result?.studentEmail)
    }
}