import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sessions.*
import kotlinx.html.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Properties

// Load config from local.properties file, fallback to environment variables (for production)
fun loadConfig(): Properties {
    val props = Properties()
    val stream = object {}.javaClass.getResourceAsStream("/local.properties")
    if (stream != null) {
        props.load(stream)
    }
    return props
}

val config = loadConfig()
val GOOGLE_CLIENT_ID = config.getProperty("GOOGLE_CLIENT_ID") ?: System.getenv("GOOGLE_CLIENT_ID") ?: ""
val GOOGLE_CLIENT_SECRET = config.getProperty("GOOGLE_CLIENT_SECRET") ?: System.getenv("GOOGLE_CLIENT_SECRET") ?: ""
val REDIRECT_URI = config.getProperty("REDIRECT_URI") ?: System.getenv("REDIRECT_URI") ?: "http://localhost:8080/callback"
val PORT = System.getenv("PORT")?.toIntOrNull() ?: 8080

@Serializable
data class UserSession(val email: String, val name: String)

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
        }

        routing {
            // Home page
            get("/") {
                val session = call.sessions.get<UserSession>()
                call.respondHtml {
                    head {
                        title { +"SJSU Google Sign-In Demo" }
                        style {
                            +"""
                                body {
                                    font-family: Arial, sans-serif;
                                    display: flex;
                                    justify-content: center;
                                    align-items: center;
                                    min-height: 100vh;
                                    margin: 0;
                                    background: linear-gradient(135deg, #0055a2 0%, #e5a823 100%);
                                }
                                .container {
                                    background: white;
                                    padding: 40px;
                                    border-radius: 10px;
                                    box-shadow: 0 4px 20px rgba(0,0,0,0.2);
                                    text-align: center;
                                }
                                h1 { color: #0055a2; }
                                .btn {
                                    display: inline-block;
                                    padding: 12px 24px;
                                    margin: 10px;
                                    border-radius: 5px;
                                    text-decoration: none;
                                    font-weight: bold;
                                    cursor: pointer;
                                }
                                .btn-google {
                                    background: #4285f4;
                                    color: white;
                                }
                                .btn-logout {
                                    background: #dc3545;
                                    color: white;
                                }
                                .user-info {
                                    background: #f8f9fa;
                                    padding: 20px;
                                    border-radius: 5px;
                                    margin-top: 20px;
                                }
                            """
                        }
                    }
                    body {
                        div("container") {
                            h1 { +"SJSU OAuth Demo" }
                            if (session != null) {
                                div("user-info") {
                                    h2 { +"Welcome!" }
                                    p { +"Name: ${session.name}" }
                                    p { +"Email: ${session.email}" }
                                }
                                a(href = "/logout", classes = "btn btn-logout") {
                                    +"Sign Out"
                                }
                            } else {
                                p { +"Sign in with your SJSU Google account" }
                                a(href = "/login", classes = "btn btn-google") {
                                    +"Sign in with Google"
                                }
                            }
                        }
                    }
                }
            }

            // Redirect to Google OAuth
            get("/login") {
                val googleAuthUrl = "https://accounts.google.com/o/oauth2/v2/auth?" +
                        "client_id=$GOOGLE_CLIENT_ID&" +
                        "redirect_uri=${REDIRECT_URI.encodeURLParameter()}&" +
                        "response_type=code&" +
                        "scope=openid%20email%20profile&" +
                        "hd=sjsu.edu"  // Restricts to SJSU domain
                call.respondRedirect(googleAuthUrl)
            }

            // OAuth callback
            get("/callback") {
                val code = call.parameters["code"]
                if (code == null) {
                    call.respondRedirect("/?error=no_code")
                    return@get
                }

                // Exchange code for token
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

                // Get user info
                val userInfo: GoogleUserInfo = httpClient.get("https://www.googleapis.com/oauth2/v2/userinfo") {
                    header("Authorization", "Bearer ${tokenResponse.accessToken}")
                }.body()

                // Save session
                call.sessions.set(UserSession(userInfo.email, userInfo.name))
                call.respondRedirect("/")
            }

            // Logout
            get("/logout") {
                call.sessions.clear<UserSession>()
                call.respondRedirect("/")
            }
        }
    }.start(wait = true)
}
