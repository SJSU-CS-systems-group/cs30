// Phase 1 load test: git-write concurrency only (autosave, submit, logout).
//
// judge.url on the load-test app instance is deliberately pointed at an unreachable port, so every
// Submit's judge call fails fast (connection refused). CodeService.submitCode() still runs its git
// write (gitService.saveSubmissionWithResult) unconditionally regardless of judge outcome, so this
// script exercises the real git-lock path without needing a working judge deployment at all.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=CS30-LOADTEST \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json git-write-phase1.js
import http from 'k6/http';
import { sleep, check } from 'k6';
import { SharedArray } from 'k6/data';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'sum-two-numbers'; // must match the problem seeded in loadtest-course.yaml

const LAB_MINUTES = Number(__ENV.LAB_MINUTES) || 75;
const HEARTBEAT_INTERVAL_S = 60;
const AUTOSAVE_INTERVAL_S = 60;
const STAGGER_WINDOW_S = Number(__ENV.STAGGER_WINDOW_S) || 300; // spreads VU arrival instead of all logging in at once
const MIN_SUBMITS_PER_STUDENT = 2;
const MAX_SUBMITS_PER_STUDENT = 5;
const POLL_INTERVAL_S = 1;

// Path to students.json is env-configurable (not a hardcoded relative path) so this script doesn't
// care whether it lives next to the fixtures or in a separate directory (e.g. scripts/ vs data/).
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

function randomSubmitTimes(sessionEndMs) {
  const count = MIN_SUBMITS_PER_STUDENT + Math.floor(Math.random() * (MAX_SUBMITS_PER_STUDENT - MIN_SUBMITS_PER_STUDENT + 1));
  const now = Date.now();
  return Array.from({ length: count }, () => now + Math.random() * (sessionEndMs - now)).sort((a, b) => a - b);
}

export default function () {
  const { email, token } = students[(__VU - 1) % students.length];

  sleep(Math.random() * STAGGER_WINDOW_S); // staggered arrival, not a synchronized burst

  const sessionEndMs = Date.now() + LAB_MINUTES * 60 * 1000;
  let nextHeartbeat = Date.now();
  let nextAutosave = Date.now();
  const submitTimes = randomSubmitTimes(sessionEndMs);
  let submitIdx = 0;
  // Monotonic counters, not just the millisecond timestamp already embedded below: guarantees every
  // autosave call's file content genuinely differs from the last. saveAutosolution (GitService.kt)
  // overwrites one fixed file per student and runs `git add -A && git commit` — identical content
  // back-to-back doesn't error (runLocalCommit tolerates "nothing to commit"), but it silently produces
  // NO commit at all, undercounting real git activity. Submit doesn't have this risk (it writes a fresh
  // timestamped filename every call), but gets a matching counter for readability in git log either way.
  let autosaveSeq = 0;
  let submitSeq = 0;

  while (Date.now() < sessionEndMs) {
    const now = Date.now();

    if (now >= nextHeartbeat) {
      // Keepalive only — not itself a target of this phase's measurement. Without it the seeded
      // session hits its 2-minute TTL and autosave/submit start 401ing well before the session ends.
      http.post(`${BASE_URL}/api/check-session`, null, authHeaders(token));
      nextHeartbeat = now + HEARTBEAT_INTERVAL_S * 1000;
    }

    if (now >= nextAutosave) {
      autosaveSeq++;
      const body = JSON.stringify({
        courseId: COURSE_ID,
        section: SECTION,
        labNumber: LAB_NUMBER,
        problemSlug: PROBLEM_SLUG,
        code: `# autosave mock: ${email} seq=${autosaveSeq} @ ${now}\nprint("autosave ${autosaveSeq} from ${email}")\n`,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/autosave`, body, authHeaders(token));
      check(res, { 'autosave accepted (202)': (r) => r.status === 202 });
      nextAutosave = now + AUTOSAVE_INTERVAL_S * 1000;
    }

    if (submitIdx < submitTimes.length && now >= submitTimes[submitIdx]) {
      submitSeq++;
      const body = JSON.stringify({
        courseId: COURSE_ID,
        section: SECTION,
        labNumber: LAB_NUMBER,
        problemName: PROBLEM_SLUG,
        studentEmail: email,
        code: `# submit mock: ${email} seq=${submitSeq} @ ${now}\nprint("submit ${submitSeq} from ${email}")\n`,
        language: 'python',
      });
      const res = http.post(`${BASE_URL}/api/code/submit`, body, authHeaders(token));
      check(res, {
        'submit git-write succeeded (filePath present, judge outcome irrelevant)': (r) => {
          try {
            const parsed = JSON.parse(r.body);
            return typeof parsed.filePath === 'string' && parsed.filePath.length > 0;
          } catch (e) {
            return false;
          }
        },
      });
      submitIdx++;
    }

    sleep(POLL_INTERVAL_S);
  }

  // Mirrors the real browser's sendBeacon exactly: token as query param, no Authorization header.
  http.post(`${BASE_URL}/api/web-logout?token=${token}`, null);
}
