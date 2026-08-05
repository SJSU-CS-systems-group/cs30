// Synchronised-burst submit test against ANY problem in the pool.
//
// All VUs fire one Submit simultaneously with no stagger, which probes the judge's concurrency ceiling
// rather than its average-rate tolerance. Parameterised so a scenario matrix (custom validator vs diff,
// interactive, tight vs wide margin, few vs many test cases) needs no new scripts — earlier rounds grew
// a near-duplicate file per problem, and the checks then drifted between copies.
//
// The submitted code is the problem's own accepted solution, read from the pool at init. Nothing is
// embedded, so what gets load-tested is byte-identical to what `bt run` grades.
//
// Run:
//   k6 run --local-ips=$(cat local-ips.txt) \
//     -e BASE_URL=http://localhost:8090 \
//     -e COURSE_ID=<course-uuid> \
//     -e PROBLEM_SLUG=<problem> \
//     -e SOLUTION_FILE=<its accepted answer> \
//     -e STUDENTS_JSON_PATH=/abs/path/students.json \
//     -e VUS=100 \
//     problem-burst.js
//
// Always quote the problem's idle headroom (from measure-problem-characteristics.sh) alongside any
// result here: a TLE at 1.2x headroom means nothing, the same TLE at 250x is a real capacity finding.
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
// The course primary-key UUID, never the course code. CodeService.submitCode resolves it via
// courseRepository.findById, so 'CS30-LOADTEST' yields "Course not found" for every request — which is
// how a full run was once wasted. No default: failing at init beats a default that cannot work.
const COURSE_ID = __ENV.COURSE_ID;
if (!COURSE_ID) throw new Error('COURSE_ID is required: pass the course primary-key UUID, not the code');

const PROBLEM_SLUG = __ENV.PROBLEM_SLUG;
if (!PROBLEM_SLUG) throw new Error('PROBLEM_SLUG is required (must be registered on this lab)');

const SECTION = Number(__ENV.SECTION) || 1;
const LAB_NUMBER = Number(__ENV.LAB_NUMBER) || 1;

const POOL = __ENV.PROBLEM_POOL;
if (!POOL) throw new Error('PROBLEM_POOL is required: path to the problem pool');
const SOLUTION_FILE = __ENV.SOLUTION_FILE || 'answer.cpp';
// open() throws at init when the file is missing — deliberately, so a typo fails before the run rather
// than producing a burst of compile errors that look like a capacity result.
const SOLUTION = open(`${POOL}/${PROBLEM_SLUG}/submissions/accepted/${SOLUTION_FILE}`);

// Derived from the file extension rather than defaulted independently. A separate LANGUAGE default is
// exactly how the baseline script ended up compiling Python as C++ for an entire run.
const EXT_TO_LANGUAGE = { cpp: 'cpp', cc: 'cpp', cxx: 'cpp', java: 'java', py: 'python' };
const LANGUAGE = EXT_TO_LANGUAGE[SOLUTION_FILE.split('.').pop().toLowerCase()] || 'cpp';

// A burst against a limited worker pool can legitimately take a long time to drain: worst case a
// submission waits ceil(maxQueueSize / maxWorkers) rounds of the wall budget before it even starts.
// Default high so k6 never client-aborts a call that is still legitimately queued server-side.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '900s';

// Set false for deliberate-overload runs where 429/504 is the expected outcome being measured, so the
// run isn't marked failed for producing exactly what it set out to produce.
const EXPECT_ALL_AC = (__ENV.EXPECT_ALL_AC || 'true').toLowerCase() === 'true';

const STUDENTS_JSON_PATH = __ENV.STUDENTS_JSON_PATH || '../students.json';
const students = new SharedArray('students', () => JSON.parse(open(STUDENTS_JSON_PATH)));

// VUS may exceed the student count. It used to be capped at students.length, which made the judge's
// admission control untestable: maxQueueSize is a semaphore permit count, so 100 concurrent requests
// against maxQueueSize=100 consume exactly 100 permits and every one is admitted — no 429 is possible
// at or below the roster size. Probing QueueFull requires more requests in flight than permits.
//
// Above the roster, tokens are reused (students[(__VU - 1) % students.length]), i.e. some students issue
// several concurrent submissions. That is legitimate — a session is not single-request, and
// ApiTokenStore checks the token per request with no concurrency limit — but it stops being a
// one-student-per-VU simulation, so only use it for capacity-edge probing, never for the coverage matrix.
const VUS = Number(__ENV.VUS) || students.length;
const OVERSUBSCRIBED = VUS > students.length;

const submitDuration = new Trend('submit_duration');
// Verdict breakdown, always emitted regardless of check outcome. success:true alone does not mean AC —
// TLE and WA are also success:true at the DTO level, which is how a run of 100 uniform TLEs was once
// reported as a 100% success.
const verdictAc = new Counter('verdict_ac');
const verdictTle = new Counter('verdict_tle');
const verdictOther = new Counter('verdict_other');
const gradedFullyAccepted = new Rate('graded_fully_accepted');
// Capacity signals, counted separately from grading verdicts: 429 is the judge's admission control
// (queue full) and 504 is the sync-wait timeout. Both are correct behaviour under overload, not errors.
const http429 = new Counter('judge_429_queue_full');
const http504 = new Counter('judge_504_sync_timeout');

export const options = {
    scenarios: {
        burst_submit: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '30m',
        },
    },
    thresholds: EXPECT_ALL_AC ? { graded_fully_accepted: ['rate==1.0'] } : {},
};

export default function () {
    const { email, token } = students[(__VU - 1) % students.length];

    const res = http.post(`${BASE_URL}/api/code/submit`, JSON.stringify({
        courseId: COURSE_ID,
        section: SECTION,
        labNumber: LAB_NUMBER,
        problemName: PROBLEM_SLUG,
        studentEmail: email,
        code: SOLUTION,
        language: LANGUAGE,
    }), {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        timeout: JUDGE_CALL_TIMEOUT,
    });
    submitDuration.add(res.timings.duration);

    if (res.status === 429) http429.add(1);
    if (res.status === 504) http504.add(1);

    let parsed = null;
    try {
        parsed = JSON.parse(res.body);
    } catch (e) {
        // leave parsed null; the checks below treat that as a failure
    }

    if (parsed?.status === 'AC') verdictAc.add(1);
    else if (parsed?.status === 'TLE') verdictTle.add(1);
    else verdictOther.add(1);

    // Deliberately stronger than "success === true": require the accepted verdict AND that every case
    // was graded and passed. total > 0 matters on its own — a package that grades nothing used to
    // return AC 0/0 because worstStatus(emptyList) defaulted to "AC".
    const fullyAccepted =
        parsed?.success === true &&
        parsed?.status === 'AC' &&
        typeof parsed?.total === 'number' && parsed.total > 0 &&
        parsed?.passed === parsed?.total;
    gradedFullyAccepted.add(fullyAccepted);

    const ok = check(res, {
        'submit persisted to git (filePath present)': () => typeof parsed?.filePath === 'string',
        'graded fully accepted (status=AC, all cases passed)': () => fullyAccepted,
    });

    // One CSV row per submission, pulled out by run-phase.sh into <run>-submissions.csv.
    //
    // Aggregates hide what matters when something goes wrong: "97% accepted" does not say WHICH three
    // students lost work, nor whether one student waited 70s while everyone else waited 7s. A p95 is
    // useless for answering "did student 0042 get graded correctly", which is the question a TA asks.
    //
    // Commas and quotes are stripped from the error field so the row can never break the CSV, and so
    // the k6 console wrapper (msg="...") stays parseable.
    const clean = (v) => String(v ?? '').replace(/[",\r\n]/g, ' ');
    console.log('ROW|' + [
        __VU,
        email,
        PROBLEM_SLUG,
        res.status,                                          // 200 / 429 queue-full / 504 sync-timeout
        parsed?.status ?? 'none',                            // AC / TLE / WA / RTE / CE
        parsed?.passed ?? '',
        parsed?.total ?? '',
        Math.round(res.timings.duration),                    // ms, includes any time spent queued
        typeof parsed?.filePath === 'string' ? 'yes' : 'no',  // did the submission reach git
        fullyAccepted ? 'yes' : 'no',
        clean(res.error),
    ].map(clean).join(','));

    if (!ok) {
        console.error(`[submit] problem=${PROBLEM_SLUG} VU=${__VU} email=${email} http=${res.status} ` +
            `error=${res.error} verdict=${parsed?.status} passed=${parsed?.passed}/${parsed?.total} ` +
            `body=${String(res.body).slice(0, 300)}`);
    }
}

// Runs exactly once, before any VU. Echoes the scenario's parameters into the captured output so the
// numbers can never be read without knowing which problem and solution produced them — a results file
// of bare timings cannot be compared against any other run.
//
// Deliberately NOT handleSummary(): defining that replaces k6's built-in end-of-test summary entirely,
// so returning a custom string there would discard every metric this script exists to collect.
export function setup() {
    console.log(`scenario=burst problem=${PROBLEM_SLUG} solution=${SOLUTION_FILE} ` +
        `language=${LANGUAGE} vus=${VUS} expect_all_ac=${EXPECT_ALL_AC}`);
    if (OVERSUBSCRIBED) {
        console.log(`NOTE: vus=${VUS} exceeds the ${students.length} seeded students, so tokens are ` +
            `reused and some students submit concurrently. Capacity-edge probing only — not a ` +
            `one-student-per-VU simulation.`);
    }
}
