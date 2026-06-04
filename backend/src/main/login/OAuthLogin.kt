import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import labx.data.LockdownViolation
import java.io.File
import java.util.Properties

fun loadConfig(): Properties {
    val props = Properties()
    val file = File("local.properties")
    if (file.exists()) {
        file.inputStream().use { props.load(it) }
    }
    return props
}

val config = loadConfig()
val GOOGLE_CLIENT_ID = config.getProperty("GOOGLE_CLIENT_ID") ?: System.getenv("GOOGLE_CLIENT_ID") ?: ""
val GOOGLE_CLIENT_SECRET = config.getProperty("GOOGLE_CLIENT_SECRET") ?: System.getenv("GOOGLE_CLIENT_SECRET") ?: ""
val REDIRECT_URI = config.getProperty("REDIRECT_URI") ?: System.getenv("REDIRECT_URI") ?: "http://localhost:8080/callback"
val PORT = System.getenv("PORT")?.toIntOrNull() ?: 8080

private const val WEB_APP_RELATIVE_PATH = "frontend/build/dist/wasmJs/developmentExecutable"

val WEB_APP_DIR = resolveWebAppDir()

fun resolveWebAppDir(): File? {
    val envPath = System.getenv("WEB_APP_DIR")?.takeIf { it.isNotBlank() }
    if (envPath != null) {
        return File(envPath).absoluteFile
    }

    val cwd = File(".").absoluteFile
    return listOf(
        File(cwd, WEB_APP_RELATIVE_PATH),
        File(cwd, "../$WEB_APP_RELATIVE_PATH")
    ).firstOrNull { it.exists() }?.canonicalFile
}

@Serializable
data class UserSession(val email: String, val name: String)

@Serializable
data class PendingLogin(val appCallback: String? = null, val state: String? = null)

@Serializable
data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("id_token") val idToken: String? = null
)

@Serializable
data class GoogleUserInfo(
    val email: String,
    val name: String = "",
    val picture: String = ""
)

fun main() {
    val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    embeddedServer(Netty, port = PORT) {
        install(Sessions) {
            cookie<UserSession>("user_session")
            cookie<PendingLogin>("pending_login")
        }
        install(ServerContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }

        routing {
            // Serve Compose web app as static files
            if (WEB_APP_DIR?.exists() == true) {
                head("/") {
                    call.respondFile(File(WEB_APP_DIR, "index.html"))
                }
                staticFiles("/", WEB_APP_DIR) {
                    default("index.html")
                }
            }

            // Redirect to Google OAuth
            get("/login") {
                val appCallback = call.parameters["app_callback"]
                val state = call.parameters["state"]
                if (appCallback != null || state != null) {
                    call.sessions.set(PendingLogin(appCallback, state))
                }
                val googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                        "client_id=$GOOGLE_CLIENT_ID&" +
                        "redirect_uri=${REDIRECT_URI.encodeURLParameter()}&" +
                        "response_type=code&" +
                        "scope=openid%20email%20profile&" +
                        "hd=sjsu.edu"
                call.respondRedirect(googleAuthUrl)
            }

            // OAuth callback
            get("/callback") {
                val code = call.parameters["code"]
                val pendingLogin = call.sessions.get<PendingLogin>()
                call.sessions.clear<PendingLogin>()
                val appCallback = pendingLogin?.appCallback

                if (code == null) {
                    val stateParam = pendingLogin?.state?.encodeURLParameter()
                    val query = buildString {
                        append("error=no_code")
                        if (stateParam != null) append("&state=$stateParam")
                    }
                    val dest = appCallback?.let { "$it?$query" } ?: "/?$query"
                    call.respondRedirect(dest)
                    return@get
                }

                try {
                    val tokenResponse: GoogleTokenResponse = httpClient.post("https://oauth2.googleapis.com/token") {
                        contentType(ContentType.Application.FormUrlEncoded)
                        setBody(
                            listOf(
                                "code" to code,
                                "client_id" to GOOGLE_CLIENT_ID,
                                "client_secret" to GOOGLE_CLIENT_SECRET,
                                "redirect_uri" to REDIRECT_URI,
                                "grant_type" to "authorization_code"
                            ).formUrlEncode()
                        )
                    }.body()

                    val userInfo: GoogleUserInfo = httpClient.get("https://www.googleapis.com/oauth2/v2/userinfo") {
                        header("Authorization", "Bearer ${tokenResponse.accessToken}")
                    }.body()

                    call.sessions.set(UserSession(userInfo.email, userInfo.name))

                    // Always include name+email so the web app can auto-login on redirect
                    val nameParam = userInfo.name.encodeURLParameter()
                    val emailParam = userInfo.email.encodeURLParameter()
                    val stateParam = pendingLogin?.state?.encodeURLParameter()
                    val query = buildString {
                        append("name=$nameParam&email=$emailParam")
                        if (stateParam != null) append("&state=$stateParam")
                    }
                    val destination = appCallback ?: "/"
                    call.respondRedirect("$destination?$query")
                } catch (e: Exception) {
                    application.log.warn("OAuth callback error: ${e.message}")
                    val stateParam = pendingLogin?.state?.encodeURLParameter()
                    val query = buildString {
                        append("error=auth_failed")
                        if (stateParam != null) append("&state=$stateParam")
                    }
                    val dest = appCallback?.let { "$it?$query" } ?: "/?$query"
                    call.respondRedirect(dest)
                }
            }

            // Lockdown violation report. TODO: persist to DB + tie to lab-session id.
            post("/violations") {
                val v = call.receive<LockdownViolation>()
                val who = call.sessions.get<UserSession>()?.email ?: "anon"
                application.log.warn(
                    "LOCKDOWN_VIOLATION user=$who kind=${v.kind} ts=${v.timestampMs} detail=${v.detail}"
                )
                call.respond(HttpStatusCode.Accepted)
            }

            // Logout
            get("/logout") {
                call.sessions.clear<UserSession>()
                call.respondRedirect("/")
            }
        }
    }.start(wait = true)
}
