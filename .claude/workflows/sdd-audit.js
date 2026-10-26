export const meta = {
  name: 'sdd-audit',
  description: 'Audit AI DIAL Core after a series of OpenSpec changes: per-request cost, spec vs code, docs, security and bounds; fix docs and small things, verify',
  whenToUse: 'After the last change of a series is archived; args: optional {base} (the commit before the series, for the per-request cost comparison)',
  phases: [
    { title: 'Sweep', detail: 'four independent auditors' },
    { title: 'Fix', detail: 'docs and small fixes; larger findings reported as follow-up changes' },
    { title: 'Verify', detail: 'checkstyle, the touched test classes, validate --all --strict, stray files' },
  ],
}

// Paths are relative to the repository root, which is the current working directory of every agent.
const OPENSPEC = 'npx -y @fission-ai/openspec@1.14.3'
const BASE = (args && args.base) || null

const FINDINGS = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          where: { type: 'string', description: 'file:line, or spec capability and requirement' },
          summary: { type: 'string' },
          evidence: { type: 'string' },
          fix: { type: 'string' },
          size: { type: 'string', enum: ['docs', 'small', 'change'], description: 'docs edit, a few lines of code, or needs its own OpenSpec change' },
        },
        required: ['severity', 'where', 'summary', 'evidence', 'fix', 'size'],
      },
    },
  },
  required: ['findings'],
}

const COMMON = `You audit AI DIAL Core, the repository at your current working directory (read CLAUDE.md, docs/CODE_STYLE.md and
openspec/specs first; the archived changes are in openspec/changes/archive, each with design.md and review.md). Do not
edit files and do not run git commands that change the working tree. Do not run Gradle (another auditor does). Every
finding needs evidence. Only real problems; an empty list is a fine answer.`

const SWEEPS = [
  {
    key: 'cost',
    prompt: `Lens: PER-REQUEST COST. For the paths the archived changes touched, count what one user request costs: Core
passes (an HTTP request that re-enters Core counts as one), body copies and parses, Redis and blob round trips,
per-request keys written. Compare with what each archived design.md claimed${BASE ? ` and with the code at commit ${BASE}` : ''}.
Report any body parsed where it is only forwarded, any re-entry the designs said would not happen, and any round trip
the designs did not state.`,
  },
  {
    key: 'spec-vs-code',
    prompt: `Lens: SPEC vs CODE. For each capability in openspec/specs, check a sample of requirements (at least the ones about
errors, limits, accounting and ordering) against the code and its tests: a requirement contradicted by the code, a
requirement with no test, behaviour the code clearly has that no spec states (especially error answers and records
written), and stale cross-references between specs.`,
  },
  {
    key: 'docs',
    prompt: `Lens: DOCS. Check docs/dynamic-settings/*.md, docs/*.md, sample/ and CLAUDE.md against the code and specs: every
config field and setting the changes added is documented with its default and validation; the commands in CLAUDE.md
"Checks" work as written; nothing describes behaviour the changes removed. Also collect every task marked "(deferred:
...)" in the archived tasks.md files (change, task id, one line) for a list of checks still to run.`,
  },
  {
    key: 'security',
    prompt: `Lens: SECURITY and BOUNDS. Caller API keys, per-request keys, upstream keys or tokens reaching logs, error bodies
or origins they should not; an origin receiving more of the request than the spec allows; unbounded memory from client
or origin input (streams piped without backpressure, buffers without a cap, caches without a bound); blocking calls on
the event loop; timers, Futures never completed or response streams never ended; timeouts and cancellation missing on
outbound calls; limits or accounting that can be bypassed through a request Core makes to itself.`,
  },
]

phase('Sweep')
const swept = (await parallel(SWEEPS.map(s => () =>
  agent(`${COMMON}\n${s.prompt}`, { label: `audit:${s.key}`, phase: 'Sweep', schema: FINDINGS })
    .then(r => r && r.findings.map(f => ({ lens: s.key, ...f }))))))
  .filter(Boolean).flat()
log(`${swept.length} findings: ${swept.filter(f => f.size === 'change').length} need a change of their own`)

phase('Fix')
const fixed = await agent(
  `Apply the audit findings below to the repository at your current working directory that are of size "docs" or
"small" (check each yourself first; reject one with a reason if it does not hold). Do not implement findings of size
"change": they become proposals later. For "small" code fixes add or adjust the JUnit / Vert.x test that covers them. Add
to openspec/audits/<date>.md the deferred tasks the docs auditor collected (change, task, one line each). Do not commit
and do not run git commands that change the working tree. Then run, with JAVA_HOME a JDK 21, ./gradlew checkstyleMain
checkstyleTest and the test classes you touched with --tests, and ${OPENSPEC} validate --specs --strict, and fix what
fails.
Findings: ${JSON.stringify(swept)}`,
  {
    label: 'fix', phase: 'Fix',
    schema: {
      type: 'object',
      properties: {
        applied: { type: 'array', items: { type: 'string' } },
        rejected: { type: 'array', items: { type: 'object', properties: { summary: { type: 'string' }, reason: { type: 'string' } }, required: ['summary', 'reason'] } },
        followUps: { type: 'array', items: { type: 'string' }, description: 'size "change" findings, one line each, as proposal briefs' },
      },
      required: ['applied', 'rejected', 'followUps'],
    },
  },
)

phase('Verify')
const verified = await agent(
  `Verify the working tree of the repository at your current working directory. Do not edit tracked files. Run, from the
repository root in Git Bash with JAVA_HOME a JDK 21:
1. ./gradlew checkstyleMain checkstyleTest; ${OPENSPEC} validate --all --strict
2. ./gradlew :server:test --tests "<every test class the archived changes added or touched, from their review.md files>"
   (not the full suite: it runs in CI). Report each class and its result.
3. git status --short: list anything untracked that is not part of the work (stray files).`,
  {
    label: 'verify', phase: 'Verify',
    schema: {
      type: 'object',
      properties: {
        passed: { type: 'boolean' },
        checks: { type: 'array', items: { type: 'string' } },
        testsRun: { type: 'array', items: { type: 'string' } },
        stray: { type: 'array', items: { type: 'string' } },
      },
      required: ['passed', 'checks', 'testsRun', 'stray'],
    },
  },
)
return { findings: swept, fixed, verified }
