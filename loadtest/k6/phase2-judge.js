// Phase 2: kt-judge capacity under concurrent Run/Submit. Judge is REAL this time (Phase 1 pointed it at
// an unreachable port deliberately; this round points it at a real, already-running kt-judge instance).
// Autosave/submit git-write checks are unchanged from Phase 1 (already judge-outcome-agnostic) — new here
// is Run, which never touches git, only the judge, and a judge-verdict check on both Run and Submit.
//
// One VU per student, one while-loop, heartbeat included — deliberately NOT split into its own scenario.
// An earlier version of this script tried splitting heartbeat into a second, independent k6 scenario to
// match the real browser's independent setInterval(checkSession, 60000) timer more faithfully (in the real
// app, heartbeat never blocks behind an in-flight Run/Submit fetch()). That split introduced a worse,
// confirmed bug of its own: this test harness uses one local IP per simulated student (via --local-ips) as
// a stand-in for "this session belongs to this IP" — but a real student's browser only ever has ONE IP for
// ALL its traffic. Two scenarios meant two independent VUs per student, and k6's --local-ips round-robin
// does not guarantee both land on the same address; when they didn't, the backend's IP-binding check
// correctly (and permanently) 401'd every call from the mismatched VU for the whole test. That's a
// self-inflicted test-harness bug, not a real risk — so it's reverted. The remaining trade-off is real but
// small: a Run/Submit call blocking this VU can delay its own next heartbeat check. With
// HEARTBEAT_INTERVAL_S=60 and a 120s session TTL, that only matters if a single call takes close to the
// full JUDGE_CALL_TIMEOUT right as a heartbeat comes due — bounded and rare, unlike the deterministic,
// whole-test failures the scenario split caused.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json phase2-judge.js
import http from 'k6/http';
import { sleep, check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend } from 'k6/metrics';

// Separate timing per endpoint type — the built-in http_req_duration lumps heartbeat/autosave/run/
// submit/logout into one aggregate, which dilutes run/submit latency with a majority of fast,
// non-judge calls. These print alongside the built-in metrics in k6's default summary automatically.
const heartbeatDuration = new Trend('heartbeat_duration');
const autosaveDuration = new Trend('autosave_duration');
const runDuration = new Trend('run_duration');
const submitDuration = new Trend('submit_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'sum-two-numbers'; // must exist under problemGitRepo (../data/problems/sum-two-numbers)

const LAB_MINUTES = Number(__ENV.LAB_MINUTES) || 75;
const HEARTBEAT_INTERVAL_S = 60;
const AUTOSAVE_INTERVAL_S = 60;
const STAGGER_WINDOW_S = Number(__ENV.STAGGER_WINDOW_S) || 300;
const MIN_SUBMITS_PER_STUDENT = 2;
const MAX_SUBMITS_PER_STUDENT = 5;
const MIN_RUNS_PER_STUDENT = 2;
const MAX_RUNS_PER_STUDENT = 5;
const POLL_INTERVAL_S = 1;
// Must scale with concurrency tier, not stay fixed: worst-case queue wait is roughly
// ceil(concurrency / maxWorkers) * 60s (the judge's wall-clock ceiling per job) before a request even
// starts executing, on top of its own execution time. Default (90s) is fine at low concurrency; pass
// -e JUDGE_CALL_TIMEOUT=240s (or similar) at higher tiers so k6 doesn't client-side-abort a call that's
// still legitimately queued server-side and mistake real queueing for a failure.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '90s';

const STUDENTS_JSON_PATH = __ENV.STUDENTS_JSON_PATH || '../students.json';
const students = new SharedArray('students', () => JSON.parse(open(STUDENTS_JSON_PATH)));

export const options = {
  scenarios: {
    steady_state: {
      executor: 'per-vu-iterations',
      vus: students.length,
      iterations: 1,
      maxDuration: `${LAB_MINUTES + 10}m`,
    },
  },
};

function authHeaders(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

function scheduleTimes(count, sessionEndMs) {
  const now = Date.now();
  return Array.from({ length: count }, () => now + Math.random() * (sessionEndMs - now)).sort((a, b) => a - b);
}

export default function () {
  const { email, token } = students[(__VU - 1) % students.length];
  sleep(Math.random() * STAGGER_WINDOW_S); // staggered arrival, not a synchronized burst

  const sessionEndMs = Date.now() + LAB_MINUTES * 60 * 1000;
  let nextHeartbeat = Date.now();
  let nextAutosave = Date.now();
  const submitCount = MIN_SUBMITS_PER_STUDENT + Math.floor(Math.random() * (MAX_SUBMITS_PER_STUDENT - MIN_SUBMITS_PER_STUDENT + 1));
  const runCount = MIN_RUNS_PER_STUDENT + Math.floor(Math.random() * (MAX_RUNS_PER_STUDENT - MIN_RUNS_PER_STUDENT + 1));
  const submitTimes = scheduleTimes(submitCount, sessionEndMs);
  const runTimes = scheduleTimes(runCount, sessionEndMs);
  let submitIdx = 0;
  let runIdx = 0;
  let autosaveSeq = 0;
  let submitSeq = 0;

  while (Date.now() < sessionEndMs) {
    const now = Date.now();

    if (now >= nextHeartbeat) {
      const res = http.post(`${BASE_URL}/api/check-session`, null, authHeaders(token));
      heartbeatDuration.add(res.timings.duration);
      if (res.status !== 200) {
        console.error(`[heartbeat] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} body=${res.body}`);
      }
      nextHeartbeat = now + HEARTBEAT_INTERVAL_S * 1000;
    }

    if (now >= nextAutosave) {
      autosaveSeq++;
      const body = JSON.stringify({
        courseId: COURSE_ID,
        section: SECTION,
        labNumber: LAB_NUMBER,
        problemSlug: PROBLEM_SLUG,
        code: `# autosave mock: ${email} seq=${autosaveSeq}\nprint("autosave ${autosaveSeq} from ${email}")\n`,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/autosave`, body, authHeaders(token));
      autosaveDuration.add(res.timings.duration);
      check(res, { 'autosave accepted (202)': (r) => r.status === 202 });
      if (res.status !== 202) {
        console.error(`[autosave] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} body=${res.body}`);
      }
      nextAutosave = now + AUTOSAVE_INTERVAL_S * 1000;
    }

    if (runIdx < runTimes.length && now >= runTimes[runIdx]) {
      const body = JSON.stringify({
        courseId: COURSE_ID,
        section: SECTION,
        labNumber: LAB_NUMBER,
        problemName: PROBLEM_SLUG,
        studentEmail: email,
        code: `print("run from ${email}")\n`,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/code/run`, body, {
        ...authHeaders(token),
        timeout: JUDGE_CALL_TIMEOUT,
      });
      runDuration.add(res.timings.duration);
      const runOk = check(res, {
        'run got a real judge verdict': (r) => {
          try {
            const parsed = JSON.parse(r.body);
            return Array.isArray(parsed.testcases) && parsed.testcases.length > 0;
          } catch (e) {
            return false;
          }
        },
      });
      if (!runOk) {
        console.error(`[run] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} body=${res.body}`);
      }
      runIdx++;
    }

    if (submitIdx < submitTimes.length && now >= submitTimes[submitIdx]) {
      submitSeq++;
      const body = JSON.stringify({
        courseId: COURSE_ID,
        section: SECTION,
        labNumber: LAB_NUMBER,
        problemName: PROBLEM_SLUG,
        studentEmail: email,
        code: `# submit mock: ${email} seq=${submitSeq}\nprint("submit ${submitSeq} from ${email}")\n`,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/code/submit`, body, {
        ...authHeaders(token),
        timeout: JUDGE_CALL_TIMEOUT,
      });
      submitDuration.add(res.timings.duration);
      const submitOk = check(res, {
        'submit git-write succeeded (filePath present)': (r) => {
          try {
            return typeof JSON.parse(r.body).filePath === 'string';
          } catch (e) {
            return false;
          }
        },
        'submit got a real judge verdict': (r) => {
          try {
            return JSON.parse(r.body).success === true;
          } catch (e) {
            return false;
          }
        },
      });
      if (!submitOk) {
        console.error(`[submit] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} body=${res.body}`);
      }
      submitIdx++;
    }

    sleep(POLL_INTERVAL_S);
  }

  // Mirrors the real browser's sendBeacon exactly: token as query param, no Authorization header.
  http.post(`${BASE_URL}/api/web-logout?token=${token}`, null);
}
