// Phase 2 staggered-arrival test against 'pascalmagic' instead of 'sum-two-numbers' — same design as
// phase2-judge.js (one VU per student, heartbeat included in the same loop — see that file's header for
// why the heartbeat-scenario-split idea was tried and reverted), but this problem has 33 real test cases
// (vs. sum-two-numbers' 1-2) and an 8s per-case time limit, so it makes the judge do meaningfully more work
// per Run/Submit call. Submits the actual verified-correct solution (Kummer/Lucas-theorem digit counting,
// checked against all 33 real test cases locally) instead of trivial mock code, so every call gets a
// genuine success:true verdict across all 33 cases.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json pascalmagic-judge.js
import http from 'k6/http';
import { sleep, check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const heartbeatDuration = new Trend('heartbeat_duration');
const autosaveDuration = new Trend('autosave_duration');
const runDuration = new Trend('run_duration');
const submitDuration = new Trend('submit_duration');
// Real, un-hideable verdict breakdown, shown in k6's own summary regardless of check pass/fail —
// success:true alone doesn't mean AC (a TLE/WA verdict is also success:true at the DTO level).
const runVerdictAc = new Counter('run_verdict_ac');
const runVerdictOther = new Counter('run_verdict_other');
const submitVerdictAc = new Counter('submit_verdict_ac');
const submitVerdictTle = new Counter('submit_verdict_tle');
const submitVerdictOther = new Counter('submit_verdict_other');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'pascalmagic'; // must exist under problemGitRepo (../data/problems/pascalmagic)

const LAB_MINUTES = Number(__ENV.LAB_MINUTES) || 75;
const HEARTBEAT_INTERVAL_S = 60;
const AUTOSAVE_INTERVAL_S = 60;
const STAGGER_WINDOW_S = Number(__ENV.STAGGER_WINDOW_S) || 300;
const MIN_SUBMITS_PER_STUDENT = 2;
const MAX_SUBMITS_PER_STUDENT = 5;
const MIN_RUNS_PER_STUDENT = 2;
const MAX_RUNS_PER_STUDENT = 5;
const POLL_INTERVAL_S = 1;
// Must scale with concurrency tier, not stay fixed — see phase2-judge.js's header for the queueing math.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '90s';

// Verified correct against all 33 real test cases (3 sample + 30 secret) locally before use here — see
// loadtest/k6/pascalmagic_solution.py.
const PASCALMAGIC_SOLUTION = `n, p = map(int, input().split())
ans = n + 1
prod = 1
while n:
    prod *= (n % p) + 1
    n //= p
ans -= prod
print(ans)
`;

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
        code: `# autosave mock: ${email} seq=${autosaveSeq}\n${PASCALMAGIC_SOLUTION}`,
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
        code: PASCALMAGIC_SOLUTION,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/code/run`, body, {
        ...authHeaders(token),
        timeout: JUDGE_CALL_TIMEOUT,
      });
      runDuration.add(res.timings.duration);
      let runParsed = null;
      try {
        runParsed = JSON.parse(res.body);
      } catch (e) {
        // leave runParsed null; checks below handle it
      }
      const runCases = Array.isArray(runParsed?.testcases) ? runParsed.testcases : [];
      const runAllAc = runCases.length > 0 && runCases.every((tc) => tc.status === 'AC');
      if (runAllAc) runVerdictAc.add(1); else runVerdictOther.add(1);
      const runOk = check(res, {
        'run got a real judge verdict (testcases present)': () => runCases.length > 0,
        // /api/code/run has no top-level status — check every returned case individually.
        // success:true only means the judge call didn't crash, not that the code is correct.
        'run fully accepted (all returned cases AC)': () => runAllAc,
      });
      if (!runOk) {
        console.error(`[run] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} cases=${runCases.map(c => c.status).join(',')} body=${res.body}`);
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
        code: PASCALMAGIC_SOLUTION,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/code/submit`, body, {
        ...authHeaders(token),
        timeout: JUDGE_CALL_TIMEOUT,
      });
      submitDuration.add(res.timings.duration);
      let submitParsed = null;
      try {
        submitParsed = JSON.parse(res.body);
      } catch (e) {
        // leave submitParsed null; checks below handle it
      }
      if (submitParsed?.status === 'AC') submitVerdictAc.add(1);
      else if (submitParsed?.status === 'TLE') submitVerdictTle.add(1);
      else submitVerdictOther.add(1);
      const submitOk = check(res, {
        'submit git-write succeeded (filePath present)': () => typeof submitParsed?.filePath === 'string',
        // Deliberately stronger than "success === true" — a TLE/WA verdict still returns
        // success:true at the DTO level (judge call didn't crash), which would make a
        // success-only check falsely pass on a genuinely failed grading run.
        'submit fully accepted (status=AC, all 33 cases passed)': () =>
          submitParsed?.success === true &&
          submitParsed?.status === 'AC' &&
          typeof submitParsed?.total === 'number' && submitParsed.total > 0 &&
          submitParsed?.passed === submitParsed?.total,
      });
      if (!submitOk) {
        console.error(`[submit] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} verdict=${submitParsed?.status} passed=${submitParsed?.passed}/${submitParsed?.total} body=${res.body}`);
      }
      submitIdx++;
    }

    sleep(POLL_INTERVAL_S);
  }

  // Mirrors the real browser's sendBeacon exactly: token as query param, no Authorization header.
  http.post(`${BASE_URL}/api/web-logout?token=${token}`, null);
}
