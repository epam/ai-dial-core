export const meta = {
  name: 'sdd-propose',
  description: 'Draft OpenSpec change proposals for AI DIAL Core, review each for fidelity to today\'s behaviour and for simplicity, revise and validate',
  whenToUse: 'args: one or more {id, brief} objects; the brief says what the change must do and names any reference it ports behaviour from',
  phases: [
    { title: 'Draft', detail: 'proposal, spec deltas, design, tasks per change' },
    { title: 'Review', detail: 'fidelity and simplicity reviewers per change' },
    { title: 'Revise', detail: 'apply findings, record them in design.md, validate --strict' },
  ],
}

// Paths are relative to the repository root, which is the current working directory of every agent.
const OPENSPEC = 'npx -y @fission-ai/openspec@1.14.3'

const items = (Array.isArray(args) ? args : [args]).filter(a => a && typeof a === 'object' && a.id)
if (items.length === 0) throw new Error('args: one or more {id, brief} objects')
for (const it of items) {
  if (!it.brief) throw new Error(`no brief for change ${it.id}`)
}
const ALL = items.map(it => `- ${it.id}`).join('\n')

const FINDINGS = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['high', 'medium', 'low'] },
          artifact: { type: 'string', description: 'proposal.md, design.md, tasks.md or the spec delta path' },
          summary: { type: 'string' },
          evidence: { type: 'string', description: 'a path:line in the code, or an artifact quote that proves it' },
          fix: { type: 'string' },
        },
        required: ['severity', 'artifact', 'summary', 'evidence', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const draft = it => agent(
  `Create the OpenSpec change "${it.id}" for AI DIAL Core, the repository at your current working directory (read its
CLAUDE.md, docs/CODE_STYLE.md, openspec/config.yaml and the specs in openspec/specs first). Planning only: do not edit
Java code.

Brief (a starting point; verify every claim in the Core source on development, which wins, and in any reference the
brief names):
${it.brief}

Steps, all from the repository root, with ${OPENSPEC} wherever OpenSpec says "openspec":
1. ${OPENSPEC} new change ${it.id}
2. ${OPENSPEC} status --change ${it.id} --json, then for each artifact in dependency order
   ${OPENSPEC} instructions <artifact> --change ${it.id} --json and write the artifact as instructed
   (proposal.md; spec deltas under specs/<capability>/spec.md with ADDED / MODIFIED / REMOVED Requirements against the
   existing capabilities; design.md; tasks.md). The schema needs design.md before tasks.md; for a small change it may be
   a few lines (Context pointing at the proposal, no decisions, Migration Plan "none") rather than a full document.
3. ${OPENSPEC} validate ${it.id} --strict until it passes.
Other changes in this run (stay out of their scope; name a dependency on one if you have it):
${ALL}
Return the change id, the capabilities touched, counts, the validate output, and any open questions or assumptions.`,
  {
    label: `draft:${it.id}`, phase: 'Draft',
    schema: {
      type: 'object',
      properties: {
        id: { type: 'string' },
        capabilities: { type: 'array', items: { type: 'string' } },
        requirements: { type: 'number' },
        scenarios: { type: 'number' },
        validateOutput: { type: 'string' },
        assumptions: { type: 'array', items: { type: 'string' } },
      },
      required: ['id', 'capabilities', 'requirements', 'scenarios', 'validateOutput', 'assumptions'],
    },
  },
)

const LENSES = [
  {
    key: 'fidelity',
    prompt: `Lens: FIDELITY to today's Core behaviour and to any reference the brief names. Read the cited Core code and the
code paths of the same flow that are not cited (the LLM controllers, the upstream route, the per-request key, token
stats, the analytics row). Find: behaviour the specs miss or state differently from the code; wrong or missing path:line
citations; a deliberate difference from today that design.md does not justify; a per-request cost (a pass, a copy, a
parse, Redis / blob round trips) the design does not state.`,
  },
  {
    key: 'simplicity',
    prompt: `Lens: SIMPLICITY and TESTABILITY. Find: scope beyond the brief or overlapping another change in this run;
speculative config or abstraction; a design that ignores an existing helper (the function chain, ProxyUtil,
DeploymentEndpointUtil, UpstreamRouteProvider) or breaks docs/CODE_STYLE.md (blocking on the event loop, Future chains
without failure handlers); scenarios that are vague or cannot be tested by a JUnit / Vert.x test; HTTP-visible behaviour
without an integration test; missing docs tasks; tasks too large to verify.`,
  },
]

const review = (d, it) => parallel(LENSES.map(l => () => agent(
  `Review the OpenSpec change "${it.id}" in openspec/changes/${it.id} (proposal.md, specs/, design.md, tasks.md) of the
repository at your current working directory, against the brief below, the specs in openspec/specs and the Core source
on development.
${l.prompt}
Do not edit any file. Every finding needs evidence (path:line, or a quote from the artifact). Return only real
problems; an empty list is a fine answer.
Brief: ${it.brief}`,
  { label: `review:${l.key}:${it.id}`, phase: 'Review', schema: FINDINGS },
).then(r => r && { lens: l.key, findings: r.findings }))).then(rs => ({ draft: d, reviews: rs.filter(Boolean) }))

const revise = (r, it) => {
  const findings = r.reviews.flatMap(x => x.findings.map(f => ({ lens: x.lens, ...f })))
  return agent(
    `Revise the OpenSpec change "${it.id}" in openspec/changes/${it.id} of the repository at your current working directory
with these review findings:
${JSON.stringify(findings, null, 1)}
For each: apply it if it holds (check the evidence yourself), or reject it with a reason. Append a section
"## Spec review" to design.md that lists every finding with lens, severity, accepted/rejected and what changed.
Then ${OPENSPEC} validate ${it.id} --strict (from the repository root) until it passes. Do not edit Java code.`,
    {
      label: `revise:${it.id}`, phase: 'Revise',
      schema: {
        type: 'object',
        properties: {
          accepted: { type: 'number' },
          rejected: { type: 'number' },
          validateOutput: { type: 'string' },
          passed: { type: 'boolean' },
        },
        required: ['accepted', 'rejected', 'validateOutput', 'passed'],
      },
    },
  ).then(v => ({ id: it.id, draft: r.draft, findings, revision: v }))
}

return await pipeline(items, it => draft(it), (d, it) => review(d, it), (r, it) => revise(r, it))
