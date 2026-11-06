export const meta = {
  name: 'sdd-apply',
  description: 'Implement one OpenSpec change in AI DIAL Core, verify it independently, review it through three lenses and fix until clean (max 2 rounds)',
  whenToUse: 'args: one change id under openspec/changes; run changes one at a time (they share files)',
  phases: [
    { title: 'Implement', detail: 'tasks.md, a test per scenario, docs' },
    { title: 'Verify', detail: 'independent checkstyle / targeted tests / validate --strict / stray files' },
    { title: 'Review', detail: 'spec conformance, correctness and security, simplicity' },
    { title: 'Fix', detail: 'fix, re-verify, recheck; at most 2 rounds' },
    { title: 'Record', detail: 'review.md in the change folder' },
  ],
}

// Paths are relative to the repository root, which is the current working directory of every agent.
const OPENSPEC = 'npx -y @fission-ai/openspec@1.14.3'
const id = typeof args === 'string' ? args : args && args.id
if (!id) throw new Error('args: a change id')
const CHANGE = `openspec/changes/${id}`

const CHECKS = `From the repository root in Git Bash, with JAVA_HOME pointing at a JDK 21 (CLAUDE.md "Checks"):
1. ./gradlew checkstyleMain checkstyleTest   (must pass)
2. ./gradlew :server:test --tests "<each test class the change adds or touches>" (and :config:test or :storage:test when
   those modules change). Not the full suite: it runs in CI after the push. List the classes you ran.
3. ${OPENSPEC} validate ${id} --strict
4. git status --short: an untracked file that belongs to no task of the change (a stray redirect target, a probe, a
   build output outside build/) is a failure; scratch files go to $TEMP`

const ISSUES = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          file: { type: 'string' },
          line: { type: 'number' },
          summary: { type: 'string' },
          scenario: { type: 'string', description: 'concrete input and wrong outcome, or the spec scenario not met' },
          fix: { type: 'string' },
        },
        required: ['severity', 'file', 'summary', 'scenario', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const VERIFY = {
  type: 'object',
  properties: {
    passed: { type: 'boolean' },
    failures: { type: 'array', items: { type: 'object', properties: { check: { type: 'string' }, output: { type: 'string' } }, required: ['check', 'output'] } },
    testsRun: { type: 'array', items: { type: 'string' }, description: 'the test classes run and their result' },
    costClaims: { type: 'string', description: 'what design.md says about Core passes, body copies and round trips per request, and whether the code matches it' },
  },
  required: ['passed', 'failures', 'testsRun', 'costClaims'],
}

const verify = round => agent(
  `Independently verify the working tree of the repository at your current working directory, where the OpenSpec change
"${id}" (${CHANGE}) is being implemented. Do not edit tracked files; you may create temporary files outside the repo.
${CHECKS}
5. Read design.md's statement of the per-request cost (Core passes, body copies and parses, Redis / blob round trips)
   and check the diff against it: a new blocking call on the event loop, a body parsed where it was only forwarded, a
   Redis call added per request, or a Future chain without a failure handler is a failure unless design.md says why.
passed is true only if every check passed. Quote the failing output (trimmed) for each failure.`,
  { label: `verify:${round}`, phase: round === 1 ? 'Verify' : 'Fix', schema: VERIFY },
)

phase('Implement')
const impl = await agent(
  `Implement the OpenSpec change "${id}" in the repository at your current working directory. Read CLAUDE.md,
docs/CODE_STYLE.md, openspec/config.yaml, everything in ${CHANGE}, and the specs it modifies; then run
${OPENSPEC} instructions apply --change ${id} --json and follow it.
- First compare design.md with the current code (earlier changes may have landed since it was written); record any
  drift and your adjustment in design.md under "## Implementation notes".
- Rebase the spec deltas onto the current openspec/specs: an earlier change may have been archived since. A MODIFIED
  requirement must start from the current text of that requirement, and a REMOVED one must still exist. Then validate.
- Work through tasks.md in order, ticking each task when done. Write the JUnit / Vert.x test for each spec scenario with
  or before the code (an integration test in the server module for HTTP-visible behaviour); update docs/dynamic-settings,
  docs/*.md and CLAUDE.md where the change affects them. Tasks marked "(deferred: ...)" stay unticked.
- Follow docs/CODE_STYLE.md: no blocking on the event loop, every Future chain handles failure, scope discipline.
- Do not commit or run git commands that change branches or history.
Before returning, run the checks:
${CHECKS}
and fix what fails.`,
  {
    label: `implement:${id}`, phase: 'Implement',
    schema: {
      type: 'object',
      properties: {
        tasksDone: { type: 'number' },
        tasksDeferred: { type: 'array', items: { type: 'string' } },
        filesChanged: { type: 'array', items: { type: 'string' } },
        testsAdded: { type: 'array', items: { type: 'string' } },
        designDrift: { type: 'string' },
        checks: { type: 'string', description: 'one line per check with its result' },
      },
      required: ['tasksDone', 'tasksDeferred', 'filesChanged', 'testsAdded', 'designDrift', 'checks'],
    },
  },
)

let ver = await verify(1)
const firstVerify = ver

const LENSES = [
  {
    key: 'conformance',
    prompt: `Lens: SPEC CONFORMANCE. For every requirement and scenario in the change's spec deltas: is it implemented as
specified, and is there a JUnit / Vert.x test that would fail without it (name it)? Are the ticked tasks really done? Is
any drift from design.md recorded? Does behaviour match the cited Core source?`,
  },
  {
    key: 'correctness',
    prompt: `Lens: CORRECTNESS and SECURITY. Bugs and edge cases at the boundaries; error paths that leave a client response
open or an origin request running; blocking calls on the event loop and Future chains without failure handlers; shared
state across Vert.x contexts and virtual threads; streams piped without backpressure; resource leaks (responses, timers,
per-request keys not invalidated); credentials, per-request keys or caller API keys reaching logs or error bodies; Redis
or blob round trips added per request; limits or accounting charged to the wrong bucket or span.`,
  },
  {
    key: 'simplicity',
    prompt: `Lens: SIMPLICITY and REUSE. Code that duplicates an existing helper (the function chain, ProxyUtil,
DeploymentEndpointUtil, UpstreamRouteProvider, FutureUtil); abstractions, options or config nobody asked for; dead code;
a body parsed or copied on a hot path where it is only forwarded; tests that are redundant or assert implementation
details instead of behaviour. Prefer deletions.`,
  },
]

phase('Review')
const reviewPrompt = l => `Review the uncommitted implementation of the OpenSpec change "${id}" in the repository at your
current working directory: git diff HEAD, plus the untracked files from git status. The spec is in ${CHANGE}; the style
rules are docs/CODE_STYLE.md.
${l.prompt}
Do not edit files. Every finding needs file, line and a concrete scenario (input and wrong outcome). Only real problems;
an empty list is a fine answer.`
let findings = (await parallel(LENSES.map(l => () =>
  agent(reviewPrompt(l), { label: `review:${l.key}`, phase: 'Review', schema: ISSUES })
    .then(r => r && r.findings.map(f => ({ lens: l.key, ...f }))))))
  .filter(Boolean).flat()
const reviewed = findings

const rounds = []
for (let round = 1; round <= 2; round++) {
  const open = findings.filter(f => f.severity !== 'low')
  if (ver.passed && open.length === 0) break
  phase('Fix')
  const fix = await agent(
    `Fix the implementation of the OpenSpec change "${id}" in the repository at your current working directory (spec in
${CHANGE}).
Review findings (high and medium; check each yourself, fix it or reject it with a reason):
${JSON.stringify(open, null, 1)}
Failed checks from the independent verification:
${JSON.stringify(ver.failures, null, 1)}
Keep tasks.md and design.md "## Implementation notes" truthful. Do not commit. Run the checks before returning:
${CHECKS}`,
    {
      label: `fix:${round}`, phase: 'Fix',
      schema: {
        type: 'object',
        properties: {
          fixed: { type: 'array', items: { type: 'string' } },
          rejected: { type: 'array', items: { type: 'object', properties: { summary: { type: 'string' }, reason: { type: 'string' } }, required: ['summary', 'reason'] } },
        },
        required: ['fixed', 'rejected'],
      },
    },
  )
  ver = await verify(round + 1)
  const recheck = await agent(
    `Recheck the fixes to the OpenSpec change "${id}" in the repository at your current working directory (git diff HEAD
plus untracked files; spec in ${CHANGE}).
These findings were raised:
${JSON.stringify(open, null, 1)}
The fixer reports: ${JSON.stringify(fix)}
For each finding: is it resolved, or is the rejection justified? Then look for new problems the fixes introduced. Return
the findings still open (unresolved, unjustified rejections, new problems); do not edit files.`,
    { label: `recheck:${round}`, phase: 'Fix', schema: ISSUES },
  )
  findings = recheck ? recheck.findings.map(f => ({ lens: 'recheck', ...f })) : []
  rounds.push({ round, open, fix, verify: ver, stillOpen: findings })
}

if (!ver.passed) log(`verification still failing after ${rounds.length} fix round(s)`)
const report = {
  id,
  implement: impl,
  firstVerify,
  reviewed,
  rounds,
  finalVerify: ver,
  remaining: findings.filter(f => f.severity !== 'low'),
}
phase('Record')
await agent(
  `Append to ${CHANGE}/review.md (create it if missing; keep what is there) a record of this implementation run of the
OpenSpec change "${id}", from the report below. Sections: "## Implementation" (tasks done and deferred, tests added,
design drift), "## Verification" (each run: passed or not, the test classes run, the cost-claim check), "## Review
findings" (a table: lens, severity, file:line, finding), one "## Fix round N" per round (fixed, rejected with reason,
still open), and "## Outcome" (high/medium findings still open, low findings left, what CI must still confirm). Plain,
factual, no praise. Edit no other file.
Report: ${JSON.stringify(report)}`,
  { label: 'record', phase: 'Record', effort: 'low' },
)
return {
  id,
  implement: impl,
  firstVerify,
  reviewed,
  rounds,
  finalVerify: ver,
  remaining: findings.filter(f => f.severity !== 'low'),
  low: reviewed.filter(f => f.severity === 'low'),
}
