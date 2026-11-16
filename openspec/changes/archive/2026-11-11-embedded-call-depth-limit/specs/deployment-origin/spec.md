# Spec Delta

## ADDED Requirements

### Requirement: Depth limit of calls from an origin plugin
Core SHALL bound a chain of calls from origin plugins. When the execution path of the request the plugin answers (the
deployments, and the routers in decision mode, from the client's request down to the plugin's own) already holds 32
names, Core SHALL serve no deployment for the call and SHALL answer it `400` with the message
`Call depth limit reached: 32`.

#### Scenario: Plugin calling its own deployment
- **WHEN** a client sends a chat completion to `app`, and `p1` answers every request for `app` by calling
  `/openai/deployments/app/chat/completions` and relaying the answer
- **THEN** `p1` receives 32 requests, the call of the 32nd gets the answer `400` with the message
  `Call depth limit reached: 32`, and the client gets that `400`
