// Burst-mode test against 'pascalmagic' instead of 'sum-two-numbers' — same design as phase2-burst.js
// (all VUs fire one Submit at once, no stagger, to find the judge's real concurrency ceiling rather than
// its average-rate tolerance), but this problem has 33 real test cases (vs. sum-two-numbers' 1-2) and an
// 8s per-case time limit, so each submission makes the judge do meaningfully more work per call. Submits
// the actual verified-correct solution (Kummer/Lucas-theorem digit counting, checked against all 33 real
// test cases locally before this script was written) instead of trivial mock code, so every submission
// gets a genuine success:true verdict across all 33 cases, not just a judge-outcome-agnostic filePath check.
//
// Session TTL is a non-issue here even though a 100-way burst against a limited worker pool can legitimately
// take a long time to drain: StudentIdentityService/ApiTokenStore only checks the token once, at the moment
// each request arrives (CodeController.kt, before any judge work starts) — the 2-minute background TTL sweep
// can never retroactively invalidate a request that already passed that check and is now just waiting for
// a judge worker slot. So every VU firing its one Submit call at test start is authenticated immediately,
// regardless of how long it then sits queued. No heartbeat scenario needed for this test.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json pascalmagic-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter } from 'k6/metrics';

const submitDuration = new Trend('submit_duration');
// Real, un-hideable verdict breakdown, shown in k6's own summary regardless of check pass/fail —
// success:true alone doesn't mean AC (a TLE/WA verdict is also success:true at the DTO level).
const verdictAc = new Counter('verdict_ac');
const verdictTle = new Counter('verdict_tle');
const verdictOther = new Counter('verdict_other'); // WA/RTE/MLE/CE/or judge-call failure

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'pascalmagic';
// A 100-way burst against a limited worker pool can legitimately take a long time to fully drain in the
// worst case. Default generously high so k6 doesn't client-abort a call that's still legitimately waiting
// its turn; override with -e JUDGE_CALL_TIMEOUT=... for a different burst size.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '900s';

// Verified correct against all 33 real test cases (3 sample + 30 secret) locally before use here — see
// loadtest/k6/pascalmagic_solution.py. Kummer/Lucas theorem: the count of entries in row n divisible by
// prime p is (n+1) minus the product of (digit+1) over n's base-p digits.
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
    code: PASCALMAGIC_SOLUTION,
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

  if (parsed?.status === 'AC') verdictAc.add(1);
  else if (parsed?.status === 'TLE') verdictTle.add(1);
  else verdictOther.add(1);

  const ok = check(res, {
    'submit git-write succeeded (filePath present)': () => typeof parsed?.filePath === 'string',
    // Deliberately stronger than "success === true" — a TLE/WA verdict still returns success:true
    // at the DTO level (judge call didn't crash), which would make a success-only check falsely
    // pass on a genuinely failed grading run. Require the actual accepted verdict AND all 33 cases.
    'submit fully accepted (status=AC, all 33 cases passed)': () =>
      parsed?.success === true &&
      parsed?.status === 'AC' &&
      typeof parsed?.total === 'number' && parsed.total > 0 &&
      parsed?.passed === parsed?.total,
  });
  if (!ok) {
    console.error(`[submit] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} verdict=${parsed?.status} passed=${parsed?.passed}/${parsed?.total} body=${res.body}`);
  }
}
