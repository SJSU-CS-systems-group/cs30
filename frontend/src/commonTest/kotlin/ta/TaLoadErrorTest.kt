package ta

import kotlin.test.Test
import kotlin.test.assertEquals

class TaLoadErrorTest {

    @Test
    fun `auth and lookup statuses map to their own messages`() {
        assertEquals(TaLoadError.SESSION_EXPIRED, TaLoadError.fromStatus(401))
        assertEquals(TaLoadError.FORBIDDEN, TaLoadError.fromStatus(403))
        assertEquals(TaLoadError.NOT_FOUND, TaLoadError.fromStatus(404))
    }

    @Test
    fun `every 5xx is a server error`() {
        listOf(500, 502, 503, 599).forEach { assertEquals(TaLoadError.SERVER_ERROR, TaLoadError.fromStatus(it)) }
    }

    @Test
    fun `any other status is unexpected`() {
        listOf(302, 400, 418, 429).forEach { assertEquals(TaLoadError.UNEXPECTED, TaLoadError.fromStatus(it)) }
    }
}
