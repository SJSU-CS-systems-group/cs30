// Burst-mode test against 'tenkindsofpeople' — a problem with a CUSTOM OUTPUT VALIDATOR ("special
// judge"/checker), unlike every other problem used in tonight's tests (pascalmagic grades by plain diff).
// Only the *default* BAPCtools validator is pre-baked into the judge's sandbox image
// (kt-judge/sandbox/Dockerfile) — a custom validator (output_validators/output_validator.py here) gets
// interpreted fresh inside every ephemeral container instead. This test isolates that per-submission cost
// under concurrency, using the same all-VUs-at-once burst design as pascalmagic-burst.js.
//
// Submits the problem's own accepted reference solution (submissions/accepted/KindsOfPeopleSolution.java)
// verbatim — no accepted Python solution exists for this problem, so this is the first load test tonight
// to exercise the judge's Java path. Verify it's genuinely accepted (`bt run` against sample+secret data)
// before trusting it in a load test, same discipline as every solution used tonight.
//
// Run:
//   k6 run --local-ips=<alias-list> -e BASE_URL=http://localhost:8090 -e COURSE_ID=<uuid> \
//     -e STUDENTS_JSON_PATH=/absolute/path/to/students.json tenkindsofpeople-burst.js
import http from 'k6/http';
import { check } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Counter, Rate } from 'k6/metrics';

const submitDuration = new Trend('submit_duration');

// THE headline metric: did grading genuinely work, end to end? One sample per submission — true
// only when the judge returned AC with every case passed. A Rate (not a Counter) on purpose: it
// always appears in the summary even when the value is 0%, and prints as "✓ n ✗ n", so a run with
// zero real passes can't look like a blank/absent metric.
//
// Why this exists: "success: true" from the backend only means the judge call didn't throw — a TLE
// or WA verdict is also success:true. An earlier version of this script checked only that flag and
// reported "100% success" on a run where every single submission was actually TLE.
const gradedFullyAccepted = new Rate('graded_fully_accepted');

// What happened instead, when it wasn't a clean AC — mutually exclusive, so these sum to the
// non-accepted total.
const verdictTle = new Counter('verdict_tle');
const verdictOtherStatus = new Counter('verdict_other_status'); // WA/RTE/MLE/CE
const verdictNoResponse = new Counter('verdict_no_response');   // null status: judge call failed/timed out

// Proof that test cases actually EXECUTED, not merely that the HTTP call returned: how many
// individual cases ran and how many of those passed, aggregated across all submissions.
const casesPassed = new Counter('testcases_passed_total');
const casesRan = new Counter('testcases_ran_total');
const casesPassedPct = new Trend('testcases_passed_pct');

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8090';
const COURSE_ID = __ENV.COURSE_ID || 'CS30-LOADTEST';
const SECTION = 1;
const LAB_NUMBER = 1;
const PROBLEM_SLUG = 'tenkindsofpeople';
// A custom-validator problem pays extra per-submission cost (validator interpretation on top of the
// solution itself) — generous default so k6 doesn't client-abort a call that's still legitimately running.
const JUDGE_CALL_TIMEOUT = __ENV.JUDGE_CALL_TIMEOUT || '900s';

// The problem's own accepted Java reference solution, verbatim — see
// problem_sources/tenkindsofpeople/submissions/accepted/KindsOfPeopleSolution.java. Public class name
// matches the filename, satisfying kt-judge's Java submission-naming requirement (JudgeStore.kt).
//
// NOTE: measured against this problem's 1.0s per-case limit, Java's slowest case (secret/11) takes
// 0.522s — only a 1.9x margin, vs 3.4x for the C++ solution below (0.297s). Under concurrent load
// that margin is consumed and borderline cases TLE, which is why LANGUAGE defaults to cpp: this
// script is meant to measure the SYSTEM, and a language sitting that close to the limit measures
// the problem's time limit instead. Use LANGUAGE=java deliberately, to study that effect.
const TENKINDSOFPEOPLE_JAVA = `import java.util.Scanner;

public class KindsOfPeopleSolution {

    public static final int MAX_RADIX_ANSWER = 7_500;

    static private int parseDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        int offset = 10;
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 10;
        }
        offset += 'z' - 'a' + 1;
        if (c >= 'A' && c <= 'Z') {
            return c - 'A' + offset;
        }
        return -1;
    }

    static private long parseInt(String s, int radix) {
        long result = 0;
        for (int i = 0; i < s.length(); i++) {
            int digit = parseDigit(s.charAt(i));
            if (digit == -1) {
                throw new NumberFormatException("invalid digit " + s.charAt(i) + " for radix " + radix);
            }
            result = result * radix + digit;
        }
        return result;
    }

    public static void main(String[] args) {
        var s = new Scanner(System.in);
        var count = s.nextInt();
        for (int i = 0; i < count; i++) {
            var a = s.next();
            var b = s.next();
            var min_ar = a.chars().map(c->parseDigit((char)c)).max().orElse(1)  + 1;
            var min_br = b.chars().map(c->parseDigit((char)c)).max().orElse(1) + 1;
            var ar = min_ar;
            var br = min_br;
            var va = parseInt(a, ar);
            var vb = parseInt(b, br);
            while (ar < MAX_RADIX_ANSWER && br < MAX_RADIX_ANSWER && va != vb) {
                if (va < vb) {
                    ar++;
                    va = parseInt(a, ar);
                } else {
                    br++;
                    vb = parseInt(b, br);
                }
            }
            if (va == vb) {
                System.out.println(va + " " + ar + " " + br);
            } else {
                System.out.println("CANNOT MAKE EQUAL");
            }
        }
    }
}
`;

// The problem's own accepted C++ reference solution, verbatim — see
// problem_sources/tenkindsofpeople/submissions/accepted/answer.cpp. Verified AC on all 11 cases at
// --cpus=1.0 (the real sandbox quota), slowest 0.297s against the 1.0s limit.
// Backslashes are doubled here so the C++ source receives a literal \n, not a JS newline.
const TENKINDSOFPEOPLE_CPP = `#include <bits/stdc++.h>
using namespace std;

typedef long long int ll;

string digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
int lookup[300];

ll conv(const string &X, ll base) {
    ll ans = 0;
    for (char c:X)
        ans = ans*base + lookup[c];
    return ans;
}

int main() {
	ios_base::sync_with_stdio(false);
	cin.tie(NULL);

    for (int i=0; i<digits.size(); ++i) lookup[digits[i]] = i;

    int N; cin>>N; while (N--) {
        string A, B; cin>>A>>B;

        int big_a=-1, big_b=-1;
        for (char c:A) big_a=max(big_a, lookup[c]);
        for (char c:B) big_b=max(big_b, lookup[c]);

        for (int a=max(2, big_a+1); a<=7500; ++a) {
            ll x = conv(A, a);
            int l=big_b, r=7501;
            while (r-l>1) {
                int b = (l+r)/2;
                ll y = conv(B, b);
                if (y>=x) r=b;
                else l=b;
            }
            if (r!=7501 && conv(B, r)==x) {
                cout<<x<<" "<<a<<" "<<r<<'\\n';
                goto done;
            }
        }
        cout<<"CANNOT MAKE EQUAL\\n";

        done:;
    }
}
`;

// Which reference solution to submit. Defaults to cpp — see the note on TENKINDSOFPEOPLE_JAVA for
// why. kt-judge accepts "cpp" (judge/config.yaml languages map) and the backend's mapLanguage()
// normalises it, so no course/DB change is needed to switch language per run.
const LANGUAGE = (__ENV.LANGUAGE || 'cpp').toLowerCase();
const SOLUTIONS = { cpp: TENKINDSOFPEOPLE_CPP, java: TENKINDSOFPEOPLE_JAVA };
const SOLUTION_SOURCE = SOLUTIONS[LANGUAGE];
if (!SOLUTION_SOURCE) {
  throw new Error(`unsupported LANGUAGE=${LANGUAGE}; expected one of ${Object.keys(SOLUTIONS).join(', ')}`);
}

const STUDENTS_JSON_PATH = __ENV.STUDENTS_JSON_PATH || '../students.json';
const students = new SharedArray('students', () => JSON.parse(open(STUDENTS_JSON_PATH)));

// Concurrency tier, overridable without regenerating fixtures: -e VUS=32 uses the first 32 students
// from a 100-student students.json. Needed to walk tiers upward and find where contention starts
// causing TLEs, instead of only ever testing at whatever size the fixture happens to be.
const VUS = Number(__ENV.VUS) || students.length;

export const options = {
  scenarios: {
    burst_submit: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '20m',
    },
  },
  // Prints a dedicated THRESHOLDS block with ✓/✗ at the top of the summary, and makes k6 exit
  // non-zero when it fails — so "grading genuinely worked" is a hard, unmissable pass/fail on the
  // run itself, not something you have to infer from a checks percentage.
  thresholds: {
    graded_fully_accepted: ['rate==1.0'],
    // Every case that ran must have passed. Complements the above: catches the case where a few
    // submissions are clean but most cases across the run failed.
    testcases_passed_pct: ['avg==100'],
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
    code: SOLUTION_SOURCE,
    language: LANGUAGE,
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

  // Derive the real outcome from the response body, tolerating every shape the backend can return:
  // a full verdict, a partial verdict, or nulls (judge call failed/timed out — CodeService.kt
  // returns success:false with every verdict field null in that case).
  const status = parsed?.status ?? null;
  const cases = Array.isArray(parsed?.testcases) ? parsed.testcases : [];
  const total = typeof parsed?.total === 'number' ? parsed.total : cases.length;
  const passed = typeof parsed?.passed === 'number'
    ? parsed.passed
    : cases.filter((c) => c.status === 'AC').length;

  // "Grading genuinely worked" means all three: the call succeeded, the verdict is AC, and every
  // case actually ran and passed. total > 0 matters — a zero-case run is the false-"AC" failure
  // mode that kt-judge's worstStatus() used to report as a pass.
  const fullyAccepted =
    parsed?.success === true && status === 'AC' && total > 0 && passed === total;

  gradedFullyAccepted.add(fullyAccepted);
  if (status === null) verdictNoResponse.add(1);
  else if (status === 'TLE') verdictTle.add(1);
  else if (!fullyAccepted) verdictOtherStatus.add(1);

  if (total > 0) {
    casesRan.add(total);
    casesPassed.add(passed);
    casesPassedPct.add((passed / total) * 100);
  }

  const ok = check(res, {
    'submit git-write succeeded (filePath present)': () => typeof parsed?.filePath === 'string',
    // Deliberately stronger than "success === true" — see gradedFullyAccepted above for why.
    'grading genuinely worked (status=AC, all cases ran and passed)': () => fullyAccepted,
  });
  if (!ok) {
    // Per-case status + timing inline, so a TLE run shows exactly WHICH cases blew the limit and
    // by how much — the thing that actually explains the failure. Isolated, this solution runs
    // every case in 0.15-0.51s against a 1.0s limit, so anything near/over 1.0s here is
    // contention, not the algorithm.
    const caseDetail = cases.length > 0
      ? cases.map((c) => `${c.name}=${c.status}@${c.timeS}s`).join(' ')
      : '(no cases returned)';
    console.error(
      `[submit] VU=${__VU} email=${email} http=${res.status} error=${res.error} error_code=${res.error_code} ` +
      `verdict=${status} passed=${passed}/${total} cases: ${caseDetail}`
    );
  }
}
