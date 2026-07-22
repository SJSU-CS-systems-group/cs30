// Mixed-workload burst: some VUs submit a deliberately infinite-looping solution (should be killed and
// marked TLE per test case), others submit the verified-correct solution — testing whether genuine TLEs
// are cleanly surfaced and cleaned up (worker slot freed, container torn down) without disrupting other,
// legitimate submissions running concurrently. Different stress dimension from pascalmagic-burst.js (many
// fast jobs) and pascalmagic-slow-burst.js (many uniformly slow-but-correct jobs).
//
// Open question this is specifically designed to observe, not assume: pascalmagic's per-case limit is 8s,
// and kt-judge's grading command keeps going through per-case timeouts (`bt run -ve -aa`) rather than
// stopping at the first one — so a truly infinite loop would take ~33 cases * 8s ~= 264s to grade fully,
// if allowed to run that long. But kt-judge's own per-submission wall-clock ceiling (runAllWallSeconds,
// dynamically extended under load) sits at a fraction of that, so it may well kill the whole grading run
// partway through instead of ever reaching a clean "33 TLE verdicts" result. Either outcome is a real,
// valid finding — this script doesn't assume which one happens.
//
// Run (quick pass first, per the plan — small VU count, mostly TLE, to see what a genuine infinite loop
// actually resolves to before running a full mixed batch):
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json -e TLE_FRACTION=0.25 pascalmagic-tle-mix-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend } from 'k6/metrics';

const submitDuration = new Trend('submit_duration');
const tleSubmitDuration = new Trend('tle_submit_duration');
const correctSubmitDuration = new Trend('correct_submit_duration');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'pascalmagic';
// Generous — a TLE submission legitimately consuming most of the wall budget, or a correct one queued
// behind several TLE ones, should still get to finish rather than being client-aborted early.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '900s';
// Fraction of VUs that submit the infinite-loop solution instead of the correct one. Deterministic by VU
// index (not random) so a given run's mix is reproducible and easy to reason about from the logs.
const TLE_FRACTION = Number(__ENV.TLE_FRACTION) || 0.25;

// Verified correct against all 33 real test cases — see pascalmagic_solution.py.
const PASCALMAGIC_SOLUTION = `n, p = map(int, input().split())
ans = n + 1
prod = 1
while n:
    prod *= (n % p) + 1
    n //= p
ans -= prod
print(ans)
`;

// Deliberately never terminates — pure CPU busy-loop, no I/O, should reliably hit bt's own per-case 8s
// wall-clock limit and get killed/marked TLE rather than hanging some other way (e.g. blocking on stdin).
const PASCALMAGIC_INFINITE_LOOP = `n, p = map(int, input().split())
while True:
    pass
`;

const STUDENTS_JSON_PATH = __ENV.STUDENTS_JSON_PATH || '../students.json';
const students = new SharedArray('students', () => JSON.parse(open(STUDENTS_JSON_PATH)));

export const options = {
  scenarios: {
    tle_mix_burst: {
      executor: 'per-vu-iterations',
      vus: students.length,
      iterations: 1,
      maxDuration: '20m',
    },
  },
};

function isTleVu(vuIndex, total, fraction) {
  // Deterministic, evenly-spread selection (not just "the first N") so TLE VUs are interleaved with
  // correct ones rather than clustered — closer to a real class where struggling submissions arrive
  // alongside working ones, not all at once.
  const tleCount = Math.round(total * fraction);
  if (tleCount <= 0) return false;
  const stride = total / tleCount;
  return Math.floor(vuIndex / stride) !== Math.floor((vuIndex - 1) / stride) || vuIndex === 0;
}

export default function () {
  const idx = (__VU - 1) % students.length;
  const { email, token } = students[idx];
  const usesTle = isTleVu(idx, students.length, TLE_FRACTION);

  const body = JSON.stringify({
    courseId: COURSE_ID,
    section: SECTION,
    labNumber: LAB_NUMBER,
    problemName: PROBLEM_SLUG,
    studentEmail: email,
    code: usesTle ? PASCALMAGIC_INFINITE_LOOP : PASCALMAGIC_SOLUTION,
    language: 'python',
  });

  const res = http.post(`${BASE_URL}/api/code/submit`, body, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    timeout: JUDGE_CALL_TIMEOUT,
  });
  submitDuration.add(res.timings.duration);
  (usesTle ? tleSubmitDuration : correctSubmitDuration).add(res.timings.duration);

  let parsed = null;
  try {
    parsed = JSON.parse(res.body);
  } catch (e) {
    // leave parsed null; checks below handle it
  }

  const ok = usesTle
    ? check(res, {
        'TLE submission: judge returned filePath (git-write still happened)': () =>
          typeof parsed?.filePath === 'string',
        'TLE submission: judge correctly reported status=TLE (not a crash/hang/false-pass)': () =>
          parsed?.status === 'TLE',
      })
    : check(res, {
        'correct submission: judge returned filePath (git-write happened)': () =>
          typeof parsed?.filePath === 'string',
        'correct submission: judge correctly reported status=AC (unaffected by concurrent TLE jobs)': () =>
          parsed?.status === 'AC' && parsed?.success === true,
      });

  if (!ok) {
    console.error(
      `[submit] VU=${__VU} email=${email} usesTle=${usesTle} status=${res.status} error=${res.error} ` +
      `error_code=${res.error_code} body=${res.body}`
    );
  }
}
