// Burst-mode test: unlike phase2-judge.js's realistic staggered arrivals (submissions scattered randomly
// across a whole lab period), this deliberately clusters every student's Submit call into the same few
// seconds — the "everyone submits right before the deadline" scenario — to find kt-judge's actual
// concurrency ceiling instead of its average-rate tolerance.
//
// Session TTL is a non-issue here even though a 100-way burst against 8 workers can legitimately take a
// long time to drain: StudentIdentityService/ApiTokenStore only checks the token once, at the moment each
// request arrives (CodeController.kt, before any judge work starts) — the 2-minute background TTL sweep
// can never retroactively invalidate a request that already passed that check and is now just waiting for
// a judge worker slot. So every VU firing its one Submit call at test start is authenticated immediately,
// regardless of how long it then sits queued. No heartbeat scenario needed for this test.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json phase2-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const submitDuration = new Trend('submit_duration');
// Informational only — this script submits trivial mock code (not a real solution), so a non-AC
// verdict is expected, not a failure. Tracked anyway so the real distribution is always visible in
// k6's own summary, not just inferred from "success: true" (which doesn't mean AC — a TLE/WA
// verdict is also success:true at the DTO level). One Counter per status: a single Counter's
// default-summary output doesn't break down by tag value, so separate metrics are the only way to
// see the real distribution without a custom handleSummary().
const verdictAc = new Counter('verdict_ac');
const verdictOther = new Counter('verdict_other'); // WA/RTE/etc — expected here, mock code isn't correct

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'sum-two-numbers';
// A 100-way burst against an 8-worker judge can legitimately take ceil(100/8) * 60s = ~13min to fully
// drain in the worst case. Default generously high so k6 doesn't client-abort a call that's still
// legitimately waiting its turn; override with -e JUDGE_CALL_TIMEOUT=... for a different burst size.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '900s';

const STUDENTS_JSON_PATH = __ENV.STUDENTS_JSON_PATH || '../students.json';
const students = new SharedArray('students', () => JSON.parse(open(STUDENTS_JSON_PATH)));

export const options = {
  scenarios: {
    burst_submit: {
      executor: 'per-vu-iterations',
      vus: students.length,
      iterations: 1,
      maxDuration: '20m',
    },
  },
};

export default function () {
  const { email, token } = students[(__VU - 1) % students.length];

  const body = JSON.stringify({
    courseId: COURSE_ID,
    section: SECTION,
    labNumber: LAB_NUMBER,
    problemName: PROBLEM_SLUG,
    studentEmail: email,
    code: `# burst submit from ${email}\nprint("burst from ${email}")\n`,
    language: 'python',
  });

  const res = http.post(`${BASE_URL}/api/code/submit`, body, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    timeout: JUDGE_CALL_TIMEOUT,
  });
  submitDuration.add(res.timings.duration);

  let parsed = null;
  try {
    parsed = JSON.parse(res.body);
  } catch (e) {
    // leave parsed null; checks below handle it
  }
  if (parsed?.status === 'AC') verdictAc.add(1); else verdictOther.add(1);

  const ok = check(res, {
    'submit git-write succeeded (filePath present)': () => typeof parsed?.filePath === 'string',
    'submit got a real judge verdict': () => parsed?.success === true,
  });
  if (!ok) {
    console.error(`[submit] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} verdict=${parsed?.status} body=${res.body}`);
  }
}
