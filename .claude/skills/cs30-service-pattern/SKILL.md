---
name: cs30-service-pattern
description: How to add a swappable service in the CS30 KMP frontend — any interface whose concrete implementation will eventually make HTTP calls to the backend (e.g. BackendService, LockdownEventService, ProblemRepository). Use when adding a new service interface, when swapping a Dummy implementation for its real Http implementation, or when deciding how to wire a service through App.kt.
---

# CS30 Service Pattern

Anything that will be backend-bound in production lives behind a service interface, so swapping it in later is a one-line change in `App.kt`. This is the pattern for adding your **next** service — several existing services (`BackendService`, `ProblemRepository`) have already been fully swapped to their real Http implementation and no longer keep a Dummy variant around; don't assume every service in the codebase still branches.

## Shape

A service is three things in one file:

```kotlin
package api  // or auth, lockdown, etc.

interface FooService {
    suspend fun doThing(req: FooRequest): FooResponse
}

class DummyFooService : FooService {
    // TODO(real-backend): replace with HttpFooService that POSTs to /api/foo
    override suspend fun doThing(req: FooRequest): FooResponse {
        println("[DummyFooService] doThing :: ${describe(req)}")
        return MockDataRepository.getFooResponse()
    }
}

// Request / response data classes
@Serializable
data class FooRequest(val id: String, val data: String)

@Serializable
data class FooResponse(val result: String)
```

**Rules:**
- Interface in `commonMain` — composables depend on interface only, not implementation
- `Dummy…` impl in the same file until Http impl exists (one file beats two)
- Request/response data classes colocated with interface
- No `object` singletons (breaks tests, causes init order bugs in wasmJs)

Real example of the shape, once fully built out — `BackendService` (`frontend/src/commonMain/kotlin/api/BackendService.kt`,
package `backend`):

```kotlin
interface BackendService {
    suspend fun runCode(req: RunRequest): RunOutput
    suspend fun testCode(req: TestRequest): TestResultsResponse
    suspend fun submitCode(req: SubmitRequest): SubmissionResult
    suspend fun lastRuntimeError(): RuntimeError
    suspend fun listSubmissions(req: SubmissionsRequest): List<SubmissionInfo>
}
```

`BackendService` no longer has a `DummyBackendService` — it was retired once `HttpBackendService` became the only
implementation anyone needed. Use the Dummy+Http shape above for a **new** service; don't go looking for
`DummyBackendService` in the current tree, it's gone.

## Http Implementation

When you add the real backend, create a sibling class in the same file:

```kotlin
class HttpFooService(
    private val baseUrl: String,
    private val getAuthHeader: () -> String?,  // desktop: "Bearer <token>"; web: null (session cookie)
) : FooService {
    override suspend fun doThing(req: FooRequest): FooResponse {
        val json = postJsonWithResponse(baseUrl, "/api/foo", Json.encodeToString(req), getAuthHeader())
        return Json.decodeFromString(json)
    }
}
```

**Key pattern — the real shared HTTP helpers** (`frontend/src/commonMain/kotlin/api/HttpClient.kt`, package `backend`):

```kotlin
expect suspend fun postJsonAuth(baseUrl: String, path: String, body: String, authHeader: String?): Int
expect suspend fun postJsonWithResponse(baseUrl: String, path: String, body: String, authHeader: String?): String
expect suspend fun getJsonWithResponse(url: String, authHeader: String?): String
expect fun getCurrentAuthHeader(): String?
```

Not `postJsonAuth(url, payload: JsonObject, token): String` — the real signature takes `baseUrl` + `path`
separately, a `body: String` (already-serialized), an `authHeader: String?` (the full header value, e.g.
`"Bearer xyz"`, not a bare token), and `postJsonAuth` **returns the HTTP status code (`Int`)**; use
`postJsonWithResponse` when you need the response body back. Desktop implements these with `HttpURLConnection`
(`api/HttpClient.desktop.kt`), web with `fetch` (`api/HttpClient.web.kt`).

## Platform-Specific Auth

- Desktop: `getCurrentAuthHeader()` returns `ApiToken.value?.let { "Bearer $it" }` — a real header string.
- Web: `getCurrentAuthHeader()` returns `null` — the browser sends the session cookie automatically via
  `credentials: 'same-origin'` in `fetch`; `authHeader` is simply unused on this platform.

Call `getCurrentAuthHeader()` at the call site (composition root or inside the Http service), not once and cached —
the token can change across the app's lifetime.

## Dependency Injection — Composition Root

**File:** `frontend/src/commonMain/kotlin/app/App.kt`

There is exactly one composition root where services are wired. `BackendService` is wired unconditionally today
(no Dummy fallback remains); `LockdownEventService` still branches, because it's the one service currently mid-way
through its swap:

```kotlin
@Composable
fun App(
    defaultReporterBaseUrl: String = "",
    studentEmail: String? = null,
) {
    val backend: BackendService = remember {
        HttpBackendService(defaultReporterBaseUrl) { getCurrentAuthHeader() }
    }

    // TODO(real-backend): swap DummyLockdownEventService for HttpLockdownEventService.
    val lockdownEvents: LockdownEventService = remember(studentEmail) {
        if (studentEmail != null) {
            CsvLockdownEventService(hook = createActivityLogSessionHook(defaultReporterBaseUrl), problemSlug = { ... })
        } else {
            DummyLockdownEventService()
        }
    }

    CodeEditorScreen(backend = backend, lockdown = lockdownEvents, /* … */)
}
```

**To swap a still-Dummy service:** change one line from `Dummy…` to `Http…`/the real impl at its `remember { }`
call in `App.kt`. Nothing else changes. That's still the acceptance test for a well-built service — just be aware
not every service in the file has a Dummy branch left to swap.

## Logging Contract for Dummy Services

Every method prints exactly one line per call:

```
[DummyFooService] methodName :: key1=value1 key2=value2
```

- Use `println(…)` — works on desktop and wasmJs (console)
- Include shapes/sizes, not raw payloads (`codeLen=1234`, not the blob)
- Log at start of method, before any mock-data fetch

## TODO Marker Convention

Every Dummy impl still awaiting its real implementation carries `// TODO(real-backend): …` at the class
declaration and/or the call site where it's wired. Run `grep -rn "TODO(real-backend)" frontend/` to find whatever is currently outstanding.

## What NOT to do

- ❌ `object FooService { … }` — not swappable, breaks tests, init order bugs
- ❌ Service calls from inside `@Composable` bodies — wrap in `LaunchedEffect { … }`
- ❌ Adding a service "just in case" (wait until needed; see `cs30-clean-code`)
- ❌ HTTP code in the dummy impl (Http impl lives in a separate class when it exists)
- ❌ Duplicating mock JSON in the dummy (always read from a single mock data source)
- ❌ Mixing auth logic in every Http service (use the shared `postJsonAuth`/`postJsonWithResponse`/`getCurrentAuthHeader` helpers instead)
- ❌ Assuming `postJsonAuth`'s old `(url, payload: JsonObject, token): String` shape — it's `(baseUrl, path, body: String, authHeader): Int` now.

## Existing Services to Model After

- `backend.BackendService` / `HttpBackendService` — Run/Test/Submit code execution (Http-only now, no Dummy)
- `lockdown.LockdownEventService` / `DummyLockdownEventService` / `CsvLockdownEventService` — the one service still
  actively branching Dummy vs. real at the composition root; the clearest live example of the full pattern
- `auth.AuthService` — OAuth login and session management

When you add the next service, this pattern applies unchanged.

## Self-check before shipping a new/changed service

- [ ] Interface lives in `commonMain`; composables/state classes depend on the interface, never the concrete class.
- [ ] If you added an Http implementation, did you use `postJsonAuth`/`postJsonWithResponse`/`getJsonWithResponse`
      (real signatures above) instead of hand-rolling `HttpURLConnection`/`fetch`?
- [ ] Does swapping `Dummy…` → `Http…` really only touch one line in `App.kt`?
- [ ] If you removed the last Dummy variant of a service (fully swapped to real), did you also delete its
      `TODO(real-backend)` marker and run `grep -rn "<ServiceName>" .claude/skills/ README.md CLAUDE.md` to find and update every cross-reference still claiming it branches?
