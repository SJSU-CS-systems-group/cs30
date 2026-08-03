---
name: cs30-testing
description: How and where to add unit tests in the CS30 KMP frontend, Spring backend, and CLI. Use when adding a test, deciding whether something needs a mock, or running the existing test suite.
---

# CS30 Unit Testing

Test infrastructure differs by module because `:frontend`/`:data` are Kotlin Multiplatform (must compile to both
JVM and wasmJs) while `:backend`/`:cli` are plain JVM/Spring modules. This is not one uniform testing story —
pick the pattern that matches the module you're in.

## Frontend/`:data` (KMP) — pure-function tests only, no mocking library

**Real test dir:** `frontend/src/commonTest/kotlin/` — e.g. `html/HtmlNormalizerTest.kt`, `theme/ContrastRatioTest.kt`.
Only `commonTest.dependencies { implementation(kotlin("test")) }` is declared
(`frontend/build.gradle.kts`) — no `kotlinx-coroutines-test`, no Compose UI testing library. **There is currently
no `desktopTest` source set and no Compose UI test infrastructure in this project** — don't assume
`createComposeRule()`/`onNodeWithText()`-style screen tests exist or are set up; if you're asked to add one, that's
new infrastructure work, not "following the existing pattern."

Shape — plain `kotlin.test` assertions against pure functions, no test doubles needed because the functions under
test have no dependencies:

```kotlin
package html

import kotlin.test.Test
import kotlin.test.assertEquals

class HtmlNormalizerTest {
    @Test
    fun testSmartPunctuationReplacement() {
        val curlyQuotes = """He said “Hello”"""
        assertEquals("""He said "Hello"""", HtmlNormalizer.normalize(curlyQuotes))
    }
}
```

`:data` currently has no tests at all — if you add shared-model tests (serialization round-trips, etc.), the
same `commonTest` + `kotlin.test` pattern applies; there's no existing example to copy from yet.

## `:backend` and `:cli` (plain JVM/Spring) — JUnit 5 + MockK

**Real test dirs:** `backend/src/test/` (e.g. `CourseServiceTest.kt`), `cli/src/test/` (e.g. `UserCliTest.kt`).
Dependencies: `testImplementation("org.springframework.boot:spring-boot-starter-test")` and
`testImplementation("io.mockk:mockk:1.13.9")` (`backend/build.gradle.kts`). These are plain JVM modules, so MockK
is fair game here — it's the frontend/`:data` KMP modules that avoid it (mockk doesn't target wasmJs).

Shape — construct the class under test directly with a mocked dependency, no Spring context needed for a plain
unit test:

```kotlin
class CourseServiceTest {
    private lateinit var courseRepository: CourseRepository
    private lateinit var courseService: CourseService

    @BeforeEach
    fun setUp() {
        courseRepository = mockk(relaxed = true)
        courseService = CourseService(courseRepository)
    }

    @Test
    fun `addStudentToCourse should return success when course exists and student not enrolled`() {
        val course = Course(code = "CS-101", section = 1, year = 2024, semester = "Fall")
        every { courseRepository.findByCodeAndYearAndSemesterAndSection("CS-101", 2024, "Fall", 1) } returns course
        every { courseRepository.save(any()) } answers { firstArg() }

        val result = courseService.addStudentToCourse("CS-101", 2024, "Fall", 1, "newstudent@test.edu")

        Assertions.assertEquals("Added student newstudent@test.edu to course CS-101 (Section 1, Semester Fall, Year 2024)", result)
    }
}
```

**Naming convention on backend/cli:** backtick-quoted, sentence-style test names (`` `should return success when X` ``)
with Given/When/Then comments — different from the frontend's plain camelCase (`testSmartPunctuationReplacement`).
Match whichever convention the module you're editing already uses.

**CLI tests** mock the service layer (`CourseService`) and a `CliOptions`/`cli` output sink, then assert on the
command's return code and what it wrote via `verify { mockCli.out(...) }` / `verify { mockCli.err(...) }` — see
`cli/src/test/UserCliTest.kt` for the full pattern across `AddStudent`/`RemoveStudent`/`FindCourse`/etc.

## Running Tests

```bash
# Frontend + :data (KMP) — runs commonTest compiled for the desktop target
./gradlew :frontend:desktopTest

# Backend (plain JVM/Spring, JUnit 5 via tasks.test { useJUnitPlatform() })
./gradlew :backend:test

# CLI
./gradlew :cli:test

# Everything
./gradlew test
```

Reports land under `<module>/build/reports/tests/<taskName>/index.html`.

## What NOT to Test

- **Compose UI screens** — no test infrastructure exists for this today; don't write a `createComposeRule()` test
  expecting it to just work.
- **`expect`/`actual` platform code** directly in `commonTest` — write a pure-function test against the `expect`
  side's logic if possible, or accept it's untestable from commonTest.
- **HTTP services** (`HttpBackendService`, etc.) with real network calls — there's no fake/mock pattern established
  for these yet in this codebase; don't invent one without checking with a human first if the setup requires more than ~5 lines beyond a simple `mockk(relaxed = true)`.
- **Private methods** — test via the public API, or extract to a separately-testable function.

## Self-check before adding a test

- [ ] Is the code you're testing in `:frontend`/`:data` (KMP — `kotlin.test` only, no mocking) or `:backend`/`:cli`
      (plain JVM — MockK is fine)? Using MockK in `commonTest` will not compile for wasmJs.
- [ ] Does a Compose UI test genuinely need to exist? A Compose UI test is required only if the behavior being tested is the composition itself (a specific node appearing or disappearing on a state change) and cannot be broken out into a pure-function test on the underlying state class. If the logic lives in a `@Stable` state class, test the state class directly — there's no UI test harness set up.
- [ ] Did you match the existing naming convention in the file/module you're adding to, rather than mixing
      backtick-sentence names into `commonTest` or camelCase names into `:backend`?

