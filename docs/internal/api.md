---
title: Backend API reference
parent: Internal
nav_order: 7
---

# Backend API Reference

Reference documentation for every HTTP endpoint exposed by `:backend` (`com.cs30.server.controller.*`).
This is a dictionary, not a tutorial — for the auth *design* (why Bearer tokens, why the 2-minute TTL,
why `login_sessions` exists), see the [login flow]({% link internal/architecture/data-flow.md %}#login).
This document only covers what's actually implemented and wired today — if an endpoint below stops
matching the code, trust the code.

Base URL in production: `https://sjsu1.cs30.app:8443` (see `cs30.backend.url` in `application.properties`).
Locally: `http://localhost:8080` unless `server.port` is overridden.

## Conventions

- **Auth header:** `Authorization: Bearer <token>`, where `<token>` comes from the `api_token` query
  param on the OAuth redirect (see [Login flow](#login-flow) below). Endpoints that require it declare
  `@RequestHeader("Authorization", required = false)` and resolve identity server-side via
  `StudentIdentityService.resolve(authHeader)` — a missing or invalid header returns `401 Unauthorized`
  with an empty body unless noted otherwise. **No endpoint trusts an identity value sent by the client**
  (a `studentEmail` field or path segment); where one is present in a request body, it's either ignored
  or compared against the resolved identity and logged on mismatch.
- **Content type:** JSON request/response bodies unless noted (`GET .../assets/**` returns raw file
  bytes with a probed `Content-Type`).
- **Errors:** endpoints generally return a 2xx/4xx status with either an empty body or a small JSON
  object; there's no unified error envelope across the API.

---

## Login flow

Handled by `OAuthController`. This is the only part of the API not called directly by the frontend as a
JSON API — `/login` and `/callback` are browser redirects (Google OAuth 2.0, `hd=sjsu.edu` restricted).

### `GET /login`
Starts the OAuth round-trip. Redirects (302) to Google's consent screen.

| Query param | Required | Purpose |
|---|---|---|
| `app_callback` | no | Desktop-only. If present, marks this as a desktop login (see below) and is echoed back as the redirect destination after `/callback` completes. |
| `state` | no | Opaque value round-tripped back to the caller, appended to the final redirect. |

### `GET /callback`
Google redirects here with `?code=...`. On success, redirects (302) to `app_callback` (desktop) or `/`
(web) with `?name=&email=&api_token=&state=`. On failure, redirects with `?error=<code>` instead:

| `error` value | Meaning |
|---|---|
| `no_code` | Google didn't return a `code` param. |
| `not_enrolled` | The Google account isn't enrolled in any course (`CourseRepository.findByStudentEmail`). |
| `session_exists` | The student already has an active session (see below) — **not a bug**, this is the one-active-session-at-a-time invariant working as intended. |
| `auth_failed` | The Google token/userinfo exchange threw. |

**One active session per student.** A new login is rejected with `error=session_exists` while the
student's existing Bearer token is still within its 2-minute TTL and hasn't been explicitly logged out.
The only legitimate ways to get a new session while an old one exists: log out first, or wait for the
old one's TTL to expire (e.g. after a device crash). See the README section linked above for the full
rationale.

**Desktop vs. web login** is distinguished by whether `app_callback` was set on `/login` — this decides
the `platform` field recorded in `login_sessions` (`ApiTokenStore` holds no state of its own — see
the [login flow]({% link internal/architecture/data-flow.md %}#login)), not a separate endpoint.

`ApiTokenStore.generate()`, called here, is what inserts the `login_sessions` row for this login —
required, not best-effort: if the insert fails, the login itself fails (`error=auth_failed`) rather than
handing out a token with no backing row.

### `POST /api/logout`
Auth: `Authorization: Bearer <token>` (required, no fallback). Revokes the token — this runs a blocking
pre-logout hook first (records a `LoggedOut` activity event and commits the activity log to git); if that
fails, the request `500`s and the session is left active rather than silently ending. Otherwise `200`
with an empty body, regardless of whether the token was valid to begin with (an already-invalid/unknown
token is a no-op, not an error).

### `POST /api/web-logout`
Auth: `Authorization` header, **or** `?token=<token>` query param (web-only fallback — used by
`navigator.sendBeacon` on tab/window close, which can't set custom headers). Same effect and same
failure mode as `/api/logout`.

### `POST /api/check-session`
Auth: `Authorization`, optional. Heartbeat endpoint — both desktop and web call this every 60s to keep
the session's TTL alive. This answers "is the client still connected," not "is the student active" — the
heartbeat fires on a fixed interval regardless of mouse/keyboard activity, so an idle-but-open tab never
expires this way.

Response `200`:
```json
{ "hasActiveSession": true, "email": "jdoe@sjsu.edu" }
```
`hasActiveSession` is `false` and `email` is `null` if the token is missing/invalid/expired — this
endpoint never 401s, since the client polls it specifically to *detect* logout/expiry. If this call is
the one that detects TTL expiry, it runs the same pre-logout hook `/api/logout` does; on the rare failure
there, this `500`s instead of returning its usual body.

---

## Labs

`LabController`, base path `/api/labs`. All three require a valid Bearer token (`401` if missing).

### `GET /api/labs/student`
Currently-active labs (`now` between `startDateTime`/`endDateTime`) across every course the
authenticated student is enrolled in. `404` if not enrolled in any course.

Response `200`: `LabResponse[]`
```ts
{ courseCode, courseId, section, year, semester, labNumber, startDateTime, endDateTime, problemGitRepo }
```

### `GET /api/labs/student/all`
Same shape as above, but every lab (past/current/future), not just active ones. `404` if not enrolled.

### `GET /api/labs/{courseId}/lab/{labNumber}/remaining`
Response `200`: `{ "remainingMs": 1234 }` — milliseconds until `endDateTime`, clamped to `≥ 0`.
Returns `remainingMs: 0` (not 404) if the course or lab number doesn't exist.

---

## Problems

`ProblemController`, base path `/api/problems`. All require a valid Bearer token.

### `GET /api/problems/lab`
Problems for the authenticated student's currently-active labs. `404` if not enrolled in any course;
`200` with an empty list if enrolled but no lab is currently active.

Response `200`: `LabProblemInfo[]` — `{ courseId, courseCode, section, labNumber, slug, title, language }`

### `GET /api/problems/{courseId}/section/{section}/lab/{labNumber}/{slug}`
HTML + CSS for one problem statement. `404` if the problem doesn't exist or the student can't access it
(enrollment/section/lab checks happen in `ProblemService.getProblemContent`).

Response `200`: `{ "html": "...", "css": "..." }`

### `GET /api/problems/{courseId}/section/{section}/lab/{labNumber}/{slug}/assets/**`
Static asset (image, etc.) referenced by a problem statement's HTML. The `**` suffix is the
repo-relative asset path. Response is the raw file with a probed `Content-Type`
(`application/octet-stream` if unrecognized). `404` if the file doesn't exist or access is denied.

---

## Code execution & submission

`CodeController`, base path `/api/code`. All require a valid Bearer token; the `studentEmail` field in
each request body is accepted for backward compatibility but **overridden** with the resolved identity
(a mismatch is logged as `[identity-mismatch]`, not rejected).

### `POST /api/code/run`
Runs code against the judge without grading/persisting a submission — used for the editor's "Run" /
custom-input flows.

Request: `RunCodeRequest` — `{ courseId, section, labNumber, problemName, studentEmail, code, language?, customStdins: [] }`

Response: `RunCodeResponse` — `{ success, message, testcases?, compileOutput? }`. `200` if `success`,
else `400`.

### `POST /api/code/submit`
Grades code against the problem's testcases and persists the submission (`GitService.saveSubmissionWithResult`).

Request: `SubmitCodeRequest` — `{ courseId, section, labNumber, problemName, studentEmail, code, language? }`

Response: `SubmitCodeResponse` — `{ success, message, status?, passed?, total?, maxTimeS?, testcases?, compileOutput?, filePath? }`.
`status` is one of `AC`, `WA`, `TLE`, `RTE`, `MLE`, `CE`. `200` if `success`, else `400`; `401` (with
`SubmitCodeResponse(false, "Unauthorized")`) if the Bearer token doesn't resolve.

### `GET /api/code/submissions`
Query params: `courseId`, `section`, `labNumber`, `problemName` (all required).

Response `200`: `SubmissionInfo[]` — `{ timestamp, passed, total, maxTimeMs?, status, filePath, code }`,
scoped to the authenticated student's own submissions only (the resolved email, not a query param).

---

## Autosave

`AutosaveController`, base path `/api/autosave`. This is a *different* mechanism from `SaveType.AUTOSAVE`
inside `/api/code` — it writes a single overwritten `autosaved-solution.<ext>` file per problem (recovery
snapshot, not history), authored as the student in git rather than the server identity.

### `POST /api/autosave`
Request: `AutosaveRequest` — `{ courseId, section, labNumber, problemSlug, code, language }`

Auth + validation order: `401` if no valid Bearer token → `404` if `courseId` doesn't exist → `403` if
the student isn't enrolled in that course → `403` if the lab isn't currently active (`lab.isActive`) →
`500` if the git write itself fails → `202 Accepted` on success.

### `GET /api/autosave/{courseId}/{section}/{labNumber}/{problemSlug}`
Returns the student's last autosaved code for a problem, so the editor can repopulate it on reopen.

Response `200`: raw code as a plain string body — `""` if no autosave exists yet (never 404 for "not
found", only for a genuinely missing course or `403` for non-enrollment).

---

## Activity / lockdown logging

`ActivityController`, base path `/api/activity`. This is the current, live lockdown-event pipeline.

### `POST /api/activity/event`
Query param: `problem` (optional, label only). Body: `LockdownViolation` — `{ kind, timestampMs, detail? }`
(`kind` is one of the `ViolationKind` enum values in `:data`).

`401` if no valid Bearer token, else `202 Accepted`. Recorded as one CSV row per event under
`section_{n}/ActivityLogs/{date}/{email}_{date}_activity.csv` in the student's course git repo
(`GitService.appendActivityLog` — not committed yet, see below).

### `POST /api/activity/commit`
No body. Commits that day's activity log CSV(s) to git (`GitService.commitActivityLog`), called when a
lockdown session ends. `401` if no valid Bearer token, else `202 Accepted`.

Also invoked internally (not via this HTTP endpoint) by the pre-logout hook described under
[Login flow](#login-flow) — every logout, explicit or TTL-triggered, commits the activity log the same
way this endpoint does, and can fail the logout if that commit fails.

---

## Courses — admin/CLI only, **no authentication**

`CourseController`, base path `/api/courses`. Plain CRUD over `CourseRepository` with **no Bearer check
at all** — every route is reachable by anyone who can reach the port. This is a deliberate scope
decision, not an oversight: course management is an instructor/CLI concern (`:cli` calls these routes
directly, e.g. `addcourse`/`addstudent`), never a student-facing flow, so it was excluded from the
student-identity security pass that hardened every other controller. **This means network exposure is
the only thing standing between "anyone" and full course CRUD — never expose this port beyond the lab
network / a trusted admin path.**

| Method | Path | Body | Response |
|---|---|---|---|
| `GET` | `/api/courses` | — | `Course[]` |
| `GET` | `/api/courses/{id}` | — | `Course` or `404` |
| `POST` | `/api/courses` | `Course` | Created `Course` |
| `PUT` | `/api/courses/{id}` | `Course` | Updated `Course` (id forced to path param) or `404` |
| `DELETE` | `/api/courses/{id}` | — | `204` or `404` |

`Course` shape (`backend/src/main/models/Course.kt`): `{ id, code, section, year, semester, startDate,
endDate, language, studentGitRepo, problemGitRepo, students: string[], labs: ScheduledLab[] }`, where
`ScheduledLab` is `{ id, labNumber, startDateTime, endDateTime, problems: Problem[] }` and `Problem` is
`{ id, name, language }`.
