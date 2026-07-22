// Same design as pascalmagic-burst.js (all VUs fire one Submit at once, no stagger), but the submitted
// solution is deliberately slow instead of fast: every test tonight graded the correct, efficient solution
// in ~5-8s total across all 33 cases. Real students submit inefficient-but-legitimate solutions too — this
// tests a different stress dimension: many *individually slow* jobs held concurrently, rather than many
// *fast* jobs at once. Logic is identical to the verified-correct solution (see pascalmagic_solution.py) —
// same Kummer/Lucas-theorem digit counting, re-verified against all 33 real test cases locally — with a
// per-case sleep added. At the default 1.5s/case × 33 cases ≈ 50s per submission, deliberately just under
// kt-judge's own per-submission wall budget (runAllWallSeconds, default 60s), so submissions still complete
// successfully rather than uniformly TLE-ing — the goal here is "slow but legitimate," not "guaranteed
// timeout". Tune via -e SLEEP_SECONDS_PER_CASE=... without touching the algorithm.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json pascalmagic-slow-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend } from 'k6/metrics';

const submitDuration = new Trend('submit_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'pascalmagic';
// Slow submissions held concurrently can legitimately take a long time to fully drain. Default generously
// high so k6 doesn't client-abort a call that's still legitimately running server-side.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '900s';

// Deliberately slow but still correct — a real dial to retune per run, not a fixed fact.
const SLEEP_SECONDS_PER_CASE = Number(__ENV.SLEEP_SECONDS_PER_CASE) || 1.5;

// Identical logic to pascalmagic_solution.py (verified against all 33 real test cases), with a per-case
// sleep injected — see pascalmagic_slow_solution.py for the standalone reference copy.
const PASCALMAGIC_SLOW_SOLUTION = `n, p = map(int, input().split())
import time
time.sleep(${SLEEP_SECONDS_PER_CASE})
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
    slow_burst_submit: {
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
    code: PASCALMAGIC_SLOW_SOLUTION,
    language: 'python',
  });

  const res = http.post(`${BASE_URL}/api/code/submit`, body, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    timeout: JUDGE_CALL_TIMEOUT,
  });
  submitDuration.add(res.timings.duration);

  const ok = check(res, {
    'submit git-write succeeded (filePath present)': (r) => {
      try {
        return typeof JSON.parse(r.body).filePath === 'string';
      } catch (e) {
        return false;
      }
    },
    'submit got a real judge verdict (all 33 cases, correct-but-slow solution)': (r) => {
      try {
        return JSON.parse(r.body).success === true;
      } catch (e) {
        return false;
      }
    },
  });
  if (!ok) {
    console.error(`[submit] VU=${__VU} email=${email} status=${res.status} error=${res.error} error_code=${res.error_code} body=${res.body}`);
  }
}
