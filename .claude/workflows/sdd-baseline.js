export const meta = {
  name: 'sdd-baseline',
  description: 'Write OpenSpec baseline specs of existing Core behaviour from the code for the listed capabilities, then check them for consistency',
  whenToUse: 'Once per capability, to give existing behaviour its openspec/specs before a change touches it; args: capability ids from CAPS (default: all)',
  phases: [
    { title: 'Write', detail: 'one agent per capability spec' },
    { title: 'Reconcile', detail: 'one reviewer: overlaps, contradictions, validate --strict' },
  ],
}

// Paths are relative to the repository root, which is the current working directory of every agent.
const OPENSPEC = 'npx -y @fission-ai/openspec@1.14.3'
const S = 'server/src/main/java/com/epam/aidial/core/server'

const FORMAT = `Write openspec/specs/<capability>/spec.md in OpenSpec's main-spec format:

# <capability> Specification

## Purpose
<2-4 sentences>

## Requirements
### Requirement: <short name>
Core SHALL <normative statement> (MUST / SHALL wording; one behaviour per requirement; under 500 characters).

#### Scenario: <short name>
- **WHEN** <condition, concrete: the request, headers, body, config>
- **THEN** <observable outcome: status, headers, body, the request sent to the origin, the record written>

Every requirement needs at least one scenario. Describe behaviour at one of the boundaries named in
openspec/config.yaml (the HTTP API and the origin request; the records written), not Java internals. Be exact: header
names, URL shapes, status codes, JSON shapes, config field names and defaults. Document what the code does TODAY,
verified by reading the code and its tests (not the docs alone); where code and docs disagree, the code wins and you
note it. Do not invent behaviour. Keep it scannable: no prose beyond Purpose.
Then run: ${OPENSPEC} validate <capability> --type spec --strict  (from the repository root) and fix until it passes.`

const CAPS = [
  {
    id: 'deployment-origin',
    focus: `The request Core sends to the endpoint serving a deployment for an LLM interface once the deployment is
resolved and the body is prepared, and how the answer is relayed and recorded: the origin URI (DeploymentEndpointUtil.
resolveRequestUri: base url plus the ingress path with the deployment name rewritten, an override path template, or the
legacy endpoint; the query carried over), the method, the client headers copied and the ones dropped (hop-by-hop,
trace, API-KEY / X-API-KEY, Authorization unless forwardAuthToken, X-DIAL-APPLICATION-*), the headers Core sets
(API-KEY = the per-request key, X-DIAL-OVERRIDE-NAME, deployment features, X-UPSTREAM-* and the cache headers for a
model with upstreams, application id and properties for schema applications, X-DIAL-DEPLOYMENT-ID for a translated
interface, Content-Length), upstream selection and retry (UpstreamRoute.next, maxRetryAttempts default 1, the
retriable statuses 429 / 502 / 503 / 504 plus retriableErrorCodes, fail / succeed, Retry-After, the error when no
upstream remains, a connect failure), the relay (status and headers minus hop-by-hop, chunked, X-Accel-Buffering: no
for SSE, X-UPSTREAM-ATTEMPTS, buffering of a non-streaming chat completion to inject statistics.usage_per_model), and
what Core records after it (token usage and limits for a 200 from a model, token stats, one analytics row, per-request
key invalidation, the client-gone case).`,
    files: `${S}/controller/BaseDeploymentPostController.java, BaseChatCompletionController.java, DeploymentPostController.java, ResponsesController.java, anthropic/MessagesBaseController.java; ${S}/upstream/UpstreamRoute.java, UpstreamRouteProvider.java, TieredBalancer.java; ${S}/util/DeploymentEndpointUtil.java, ProxyUtil.java; Proxy.java (header names); the *Test classes beside them under server/src/test`,
  },
  {
    id: 'app-to-model-accounting',
    focus: `What happens when a deployment calls Core back with the per-request key it received: what the key carries
(original key, extracted claims, trace id, span id = the parent Core span, source deployment, execution path,
interceptor state, http headers, attached resources, per-request shares), its TTL, its invalidation when the parent
request ends and the 401 afterwards; the child request's context (trace id and parent span from the key, source
deployment); limits charged to the initiator's bucket (BucketBuilder); consent and accessible_by_per_request_key;
token-stats spans (startSpan / endSpan / updateDeploymentStats, the roll-up of aggCost and usagePerModel to the
ancestors, recordAggregatedCost); one analytics row per Core pass with parentDeployment; the statistics.usage_per_model
injection; a child that reports after the root ended.`,
    files: `${S}/data/ApiKeyData.java, ${S}/security/ApiKeyStore.java, ${S}/ProxyContext.java, ${S}/util/BucketBuilder.java, ${S}/token/TokenStatsTracker.java, ${S}/token/TraceContext.java, ${S}/limiter/RateLimiter.java, ${S}/log/AnalyticsLogContext.java, ${S}/util/UsagePerModelInjector.java, ${S}/service/ConsentService.java; the *Test classes beside them`,
  },
  {
    id: 'deployment-interfaces-config',
    focus: `How a deployment declares what serves each interface type: endpoint / responsesEndpoint / baseUrl and
interfaces.<type> (baseUrl, mode passthrough | translator, translator by name or inline, defaults, defaultHeaders,
features, path overrides); what the listing APIs advertise against what the request path serves (503 for a reference
that resolves to nothing); the validation in ConfigPostProcessor (translators, interfaces, mutual exclusions, unknown
names); the documented shapes in docs/dynamic-settings.`,
    files: `config/src/main/java/com/epam/aidial/core/config/Deployment.java, DeploymentInterface.java, InterfaceMode.java, Translator.java, TranslatorRef.java; ${S}/config/ConfigPostProcessor.java; ${S}/util/DeploymentEndpointUtil.java; ${S}/controller/ModelController.java, anthropic/AnthropicModelController.java; docs/dynamic-settings/*.md; the *Test classes beside them`,
  },
]

const wanted = Array.isArray(args) ? args : (args && Array.isArray(args.caps) ? args.caps : (typeof args === 'string' ? [args] : null))
const caps = wanted ? CAPS.filter(c => wanted.includes(c.id)) : CAPS
if (caps.length === 0) throw new Error(`no capability matched; known: ${CAPS.map(c => c.id).join(', ')}`)
const names = CAPS.map(c => c.id).join(', ')

const RESULT = {
  type: 'object',
  properties: {
    capability: { type: 'string' },
    requirements: { type: 'array', items: { type: 'string' } },
    validated: { type: 'boolean' },
    codeVsDocs: { type: 'array', items: { type: 'string' }, description: 'disagreements found, code wins' },
  },
  required: ['capability', 'requirements', 'validated'],
}

phase('Write')
const written = await parallel(caps.map(c => () => agent(
  `You write one OpenSpec baseline spec for AI DIAL Core, the repository at your current working directory (read its
CLAUDE.md, docs/CODE_STYLE.md and openspec/config.yaml first). Do not edit Java code.
Capability: ${c.id}
Scope: ${c.focus}
Start from: ${c.files}
The other capabilities (${names}) may be written in parallel by others. Stay inside your scope; refer to another
capability by name instead of restating it.
${FORMAT}
Write only that one file.`,
  { label: `spec:${c.id}`, phase: 'Write', schema: RESULT },
)))

phase('Reconcile')
const summary = written.filter(Boolean).map(w => `${w.capability}: ${w.requirements.length} requirements, validated=${w.validated}`).join('\n')
const reconciled = await agent(
  `OpenSpec baseline specs were just written under openspec/specs in the repository at your current working directory:
${summary}
Read them, the other specs already there, and the Core code they describe. Fix, by editing the spec files:
- the same behaviour specified in two capabilities (keep it in the owning one, refer to it from the other);
- contradictions between specs, or with the code (the code wins);
- missing behaviour that the code clearly has and no spec covers;
- scenarios that are not testable or not concrete.
Do not change Java code. Then run ${OPENSPEC} validate --specs --strict from the repository root until it passes.
Return what you changed and the final validate output.`,
  {
    label: 'reconcile', phase: 'Reconcile',
    schema: {
      type: 'object',
      properties: {
        changes: { type: 'array', items: { type: 'string' } },
        validateOutput: { type: 'string' },
        passed: { type: 'boolean' },
      },
      required: ['changes', 'validateOutput', 'passed'],
    },
  },
)
return { written, reconciled }
