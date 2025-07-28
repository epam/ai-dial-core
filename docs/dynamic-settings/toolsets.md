# Dynamic Setting for Toolsets

In dynamic settings you can include toolsets and their parameters you with to enable in DIAL.

|Parameter | Description  |
|----------|----------|
| toolsets| A list of deployed DIAL Toolsets and their parameters: `toolset_name` is an unique toolset name.               |
| toolsets.<toolset_name>              | `endpoint`: DIAL Toolset API for MCP calls. <br />`iconUrl`: Icon path for the DIAL toolset on UI.<br />`description`: Brief DIAL toolset description.<br />`displayName`: DIAL toolset name on UI. <br />`userRoles`: a specific claim value provided by a specific IDP. Refer to [IDP Configuration](https://github.com/epam/ai-dial/blob/main/docs/tutorials/2.devops/2.auth-and-access-control/3.configure-idps/0.overview.md) to view examples.<br />`descriptionKeywords`: a list of keywords describes the application, e.g. `code-gen`, `text2image`. <br />`maxRetryAttempts`: max retry attempts to route a single user request to the application's endpoint. <br />`author`: the application's developer.  <br />`createdAt`: the date of the application creation. <br />`updatedAt`: the date of the last application update. </br> `transport` - transport supported by MCP server. The available options are: `HTTP` or `SSE`. <\br> `allowedTools` - a list of available tools in the MCP server.              |

**Configuration Example:**

```json
TBD
```