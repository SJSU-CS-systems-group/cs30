// Phase 0: single-user baseline across EVERY student-facing endpoint.
//
// Why this exists: every load test so far measured submit (and sometimes run) under concurrency, with
// no idea what a single, uncontended request costs. That made every degraded number un-interpretable —
// "secret/11 took 2.1s" means nothing without knowing it takes 0.29s alone. This run establishes the
// denominator for every later phase.
//
// It is deliberately ONE VU: this is not a load test. It measures the uncontended cost and, just as
// importantly, proves each endpoint actually works with real payloads before any concurrency is added.
// Several endpoints below have never been exercised by any load test at all (activity events, latest
// autosave, problem statement/assets, submissions list, queue-status).
//
// Every check verifies the RESPONSE SHAPE, not just HTTP 200. The whole reason this suite needed
// redoing is that `success === true` was treated as "grading worked" when it only meant "no exception".
//
// Run:
//   k6 run -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> -e PROBLEM_SLUG=<slug> \
//     -e STUDENTS_JSON_PATH=/abs/path/students.json -e ITERATIONS=10 \
//     --out json=results/baseline-raw.json baseline-single-user.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
// The course primary-key UUID, never the course code. CodeService.submitCode resolves it via
// courseRepository.findById, so 'CS30-LOADTEST' yields "Course not found" for every request — which is
// how a full run was once wasted. No default: failing at init beats a default that cannot work.
const COURSE_ID = __ENV.COURSE_ID;
if (!COURSE_ID) throw new Error('COURSE_ID is required: pass the course primary-key UUID, not the code');
const SECTION = Number(__ENV.SECTION) || 1;
const LAB_NUMBER = Number(__ENV.LAB_NUMBER) || 1;
const PROBLEM_SLUG = __ENV.PROBLEM_SLUG;
if (!PROBLEM_SLUG) throw new Error('PROBLEM_SLUG is required');
const ITERATIONS = Number(__ENV.ITERATIONS) || 10;
// Judge calls are the only slow ones; generous so a baseline is never a client-side timeout.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '300s';
// Submit writes a real git commit per call. Off by default so a baseline can be re-run freely
// without inflating the student repo; enable deliberately with -e INCLUDE_SUBMIT=true.
const INCLUDE_SUBMIT = (__ENV.INCLUDE_SUBMIT || 'false').toLowerCase() === 'true';

// The solution submitted to run/submit, read from the problem's own submissions/accepted/ in the pool.
//
// It used to be a Python string literal with LANGUAGE defaulting to 'python'. Passing -e LANGUAGE=cpp
// then changed the language without changing the code, so the judge compiled Python as C++ and every
// /api/code/run call failed with:
//   submission.cpp:1:1: error: 'n' does not name a type
//     1 | n, p = map(int, input().split())
// The baseline measured a guaranteed-failing path 10 times over.
//
// Two defences against that recurring: the source comes from the package rather than a literal, and
// LANGUAGE is *derived from the file extension* instead of being independently defaulted.
const PROBLEM_POOL = __ENV.PROBLEM_POOL;
if (!PROBLEM_POOL) throw new Error('PROBLEM_POOL is required: path to the problem pool');
const SOLUTION_FILE = __ENV.SOLUTION_FILE || 'answer.cpp';   // override per problem

// open() throws at init if the file is absent, which is the wanted behaviour — better to fail before
// the run than to measure a path that cannot pass. Solution filenames vary by problem, so pass -e SOLUTION_FILE=<name>
// explicitly, or -e SOLUTION_SOURCE=<code> together with an explicit -e LANGUAGE.
const SOLUTION = __ENV.SOLUTION_SOURCE ||
    open(`${PROBLEM_POOL}/${PROBLEM_SLUG}/submissions/accepted/${SOLUTION_FILE}`);

// CodeService.getExtension/mapToJudgeLanguage accept these spellings (CodeService.kt:204-224).
const EXT_TO_LANGUAGE = { cpp: 'cpp', cc: 'cpp', cxx: 'cpp', java: 'java', py: 'python' };
const LANGUAGE = __ENV.LANGUAGE ||
    EXT_TO_LANGUAGE[SOLUTION_FILE.split('.').pop().toLowerCase()] ||
    'cpp';

const STUDENTS_JSON_PATH = __ENV.STUDENTS_JSON_PATH || '../students.json';
const students = new SharedArray('students', () => JSON.parse(open(STUDENTS_JSON_PATH)));

// Endpoint key -> metric-name suffix. Single source for the Trend name, the Rate name, and the
// threshold key, because deriving them separately is exactly what broke this script: the Rates were
// built from these object keys (camelCase, giving `ok_checkSession`) while the thresholds used the
// Trend names (snake_case, `ok_check_session`). Every threshold but `ok_health` therefore named a
// metric that did not exist, and k6 refused to start the run at all:
//   "invalid threshold defined on ok_lab_remaining; reason: no metric name "ok_lab_remaining" found"
const ENDPOINTS = {
    health: 'health',
    checkSession: 'check_session',
    problemsLab: 'problems_lab',
    problemStatement: 'problem_statement',
    labRemaining: 'lab_remaining',
    queueStatus: 'queue_status',
    autosavePost: 'autosave_post',
    autosaveGet: 'autosave_latest',
    activityEvent: 'activity_event',
    submissionsList: 'submissions_list',
    codeRun: 'code_run',
    codeSubmit: 'code_submit',
};

// One Trend per endpoint — the built-in http_req_duration aggregates everything into a single number,
// which is useless when a fast heartbeat is averaged with a multi-second submit.
//
// Per-endpoint success Rate alongside it. Always emitted (even at 0%), so a broken endpoint can't
// vanish from the summary the way a never-incremented Counter does.
const T = {};
const OK = {};
for (const [key, name] of Object.entries(ENDPOINTS)) {
    T[key] = new Trend(`ep_${name}`);
    OK[key] = new Rate(`ok_${name}`);
}

// Built from the same map, so a renamed endpoint cannot leave a dangling threshold behind.
// code_submit is excluded unless INCLUDE_SUBMIT is set: its Rate would never be populated, and a
// rate==1.0 threshold on an empty metric is not a meaningful assertion.
const OK_THRESHOLDS = {};
for (const [key, name] of Object.entries(ENDPOINTS)) {
    if (key === 'codeSubmit' && !INCLUDE_SUBMIT) continue;
    OK_THRESHOLDS[`ok_${name}`] = ['rate==1.0'];
}

export const options = {
    scenarios: {
        baseline: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: ITERATIONS,
            maxDuration: '30m',
        },
    },
    // A baseline is only meaningful if every endpoint actually worked. Any failure fails the run.
    thresholds: OK_THRESHOLDS,
};

function auth(token) {
    return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

// Records timing + success for one endpoint and logs the body when the shape check fails, so a
// failure is diagnosable from the run output alone rather than needing a server-log dig.
function measure(key, label, res, shapeOk) {
    T[key].add(res.timings.duration);
    const passed = check(res, { [label]: () => shapeOk(res) });
    OK[key].add(passed);
    if (!passed) {
        console.error(`[${key}] status=${res.status} error=${res.error} body=${String(res.body).slice(0, 400)}`);
    }
    return passed;
}

function json(res) {
    try {
        return JSON.parse(res.body);
    } catch (e) {
        return null;
    }
}

export default function () {
    // Pinned per VU, NOT per iteration.
    //
    // This was `students[__ITER % students.length]`, which rotated to a different student every
    // iteration. Each seeded session starts its 2-minute TTL at seed time, and a student that has not
    // been used yet has never been heartbeated — so the moment a run's wall-clock passed 120s, every
    // remaining iteration picked a cold, already-expired session and got 401s. It looked like sessions
    // failing to refresh; it was the script reaching for sessions it had never touched:
    //
    //   a 33-test problem     10 iters x  8.5s =  85s -> never crosses 120s -> 10 ok / 0 fail
    //   an 8-test problem     19 iters x  6.5s = 124s -> crosses on #20     -> 19 ok / 1 fail
    //   a 9-test custom one   12 iters x 10.4s = 125s -> crosses on #13     -> 12 ok / 8 fail
    //
    // Pinning to the VU means the same session is heartbeated by every iteration's /api/check-session,
    // which refreshes last_heartbeat_at and keeps it alive indefinitely (verified: a token heartbeated
    // every 20s stayed active for 200s, well past the TTL). It is also what "single-user baseline"
    // should mean — one student's experience, not a different one each time round.
    const { email, token } = students[(__VU - 1) % students.length];
    const a = auth(token);

    // ---- unauthenticated liveness -------------------------------------------------------------
    measure('health', 'GET /health returns ok', http.get(`${BASE_URL}/health`), (r) => r.status === 200);

    // ---- session / heartbeat ------------------------------------------------------------------
    measure(
        'checkSession',
        'POST /api/check-session resolves this student',
        http.post(`${BASE_URL}/api/check-session`, null, a),
        (r) => {
            const b = json(r);
            return r.status === 200 && b?.hasActiveSession === true && b?.email === email;
        }
    );

    // ---- problem discovery + statement --------------------------------------------------------
    measure(
        'problemsLab',
        'GET /api/problems/lab lists the configured problem',
        http.get(`${BASE_URL}/api/problems/lab`, a),
        (r) => {
            const b = json(r);
            return r.status === 200 && Array.isArray(b) && b.some((p) => p.slug === PROBLEM_SLUG || p.name === PROBLEM_SLUG);
        }
    );

    measure(
        'problemStatement',
        'GET problem statement returns non-empty html',
        http.get(
            `${BASE_URL}/api/problems/${COURSE_ID}/section/${SECTION}/lab/${LAB_NUMBER}/${PROBLEM_SLUG}`,
            a
        ),
        (r) => {
            const b = json(r);
            return r.status === 200 && typeof b?.html === 'string' && b.html.length > 0;
        }
    );

    // ---- lab timer ----------------------------------------------------------------------------
    // Returns 404 for a missing course/lab (changed from a misleading 200/remainingMs=0), so a 200
    // here also confirms the lab is genuinely active.
    measure(
        'labRemaining',
        'GET lab remaining returns a positive remainingMs',
        http.get(`${BASE_URL}/api/labs/${COURSE_ID}/lab/${LAB_NUMBER}/remaining`, a),
        (r) => {
            const b = json(r);
            return r.status === 200 && typeof b?.remainingMs === 'number' && b.remainingMs > 0;
        }
    );

    // ---- judge load snapshot ------------------------------------------------------------------
    measure(
        'queueStatus',
        'GET /api/code/queue-status reports judge limits',
        http.get(`${BASE_URL}/api/code/queue-status`, a),
        (r) => {
            const b = json(r);
            return r.status === 200 && typeof b?.inFlight === 'number' && b?.maxWorkers > 0 && b?.maxQueueSize > 0;
        }
    );

    // ---- autosave round trip ------------------------------------------------------------------
    // Unique marker per iteration: saveAutosave overwrites one fixed file and commits; identical
    // content back-to-back produces NO commit, which would silently under-count git activity.
    const marker = `# baseline ${email} iter=${__ITER} ts=${Date.now()}`;
    measure(
        'autosavePost',
        'POST /api/autosave accepted (202)',
        http.post(
            `${BASE_URL}/api/autosave`,
            JSON.stringify({
                courseId: COURSE_ID,
                section: SECTION,
                labNumber: LAB_NUMBER,
                problemSlug: PROBLEM_SLUG,
                code: `${marker}\n${SOLUTION}`,
                language: LANGUAGE,
            }),
            a
        ),
        (r) => r.status === 202
    );

    // Reads back what was just written — verifies the autosave actually persisted, not merely that
    // the POST was accepted.
    measure(
        'autosaveGet',
        'GET latest autosave returns the code just written',
        http.get(
            `${BASE_URL}/api/autosave/${COURSE_ID}/${SECTION}/${LAB_NUMBER}/${PROBLEM_SLUG}`,
            a
        ),
        (r) => r.status === 200 && String(r.body).includes(`iter=${__ITER}`)
    );

    // ---- lockdown activity event --------------------------------------------------------------
    // Never load-tested before, and it writes a CSV row + git commit per event on the same repo lock
    // that autosave and submit contend for.
    measure(
        'activityEvent',
        'POST /api/activity/event accepted',
        http.post(
            `${BASE_URL}/api/activity/event`,
            JSON.stringify({ kind: 'FocusLoss', timestampMs: Date.now(), detail: `baseline iter=${__ITER}` }),
            a
        ),
        (r) => r.status === 200 || r.status === 202 || r.status === 204
    );

    // ---- submissions history ------------------------------------------------------------------
    measure(
        'submissionsList',
        'GET /api/code/submissions returns an array',
        http.get(
            `${BASE_URL}/api/code/submissions?courseId=${COURSE_ID}&section=${SECTION}` +
            `&labNumber=${LAB_NUMBER}&problemName=${PROBLEM_SLUG}`,
            a
        ),
        (r) => r.status === 200 && Array.isArray(json(r))
    );

    // ---- judge: run (sample cases only, no git write) -----------------------------------------
    measure(
        'codeRun',
        'POST /api/code/run graded every sample case AC',
        http.post(
            `${BASE_URL}/api/code/run`,
            JSON.stringify({
                courseId: COURSE_ID,
                section: SECTION,
                labNumber: LAB_NUMBER,
                problemName: PROBLEM_SLUG,
                studentEmail: email,
                code: SOLUTION,
                language: LANGUAGE,
                customStdins: [],
            }),
            { ...a, timeout: JUDGE_CALL_TIMEOUT }
        ),
        (r) => {
            const b = json(r);
            const cases = Array.isArray(b?.testcases) ? b.testcases : [];
            return r.status === 200 && cases.length > 0 && cases.every((c) => c.status === 'AC' || c.status === null);
        }
    );

    // ---- judge: submit (writes a git commit) --------------------------------------------------
    if (INCLUDE_SUBMIT) {
        measure(
            'codeSubmit',
            'POST /api/code/submit fully accepted (AC, all cases passed)',
            http.post(
                `${BASE_URL}/api/code/submit`,
                JSON.stringify({
                    courseId: COURSE_ID,
                    section: SECTION,
                    labNumber: LAB_NUMBER,
                    problemName: PROBLEM_SLUG,
                    studentEmail: email,
                    code: SOLUTION,
                    language: LANGUAGE,
                }),
                { ...a, timeout: JUDGE_CALL_TIMEOUT }
            ),
            (r) => {
                const b = json(r);
                return (
                    r.status === 200 &&
                    b?.success === true &&
                    b?.status === 'AC' &&
                    typeof b?.total === 'number' &&
                    b.total > 0 &&
                    b?.passed === b?.total
                );
            }
        );
    }
}
