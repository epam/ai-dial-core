# Dynamic Setting for Toolsets

In dynamic settings you can include toolsets and their parameters you wish to enable in DIAL.

## toolsets

A list of deployed DIAL Toolsets and their [parameters](#toolsetstoolset_name): 

* `<toolset_name>` is an unique toolset name.   

### toolsets.<toolset_name>

An object containing parameters for each [toolset](#toolsets).

* `endpoint`: DIAL Toolset API for MCP calls. 
* `iconUrl`: A string with the URL with the icon location to display for the toolset on UI.
* `description`: A brief DIAL toolset description.
* `displayName`: A string with the toolset's name. Display name is shown in all DIAL client UI dropdowns, tables, and logs so operators can quickly identify the toolset.
* `userRoles`: A specific claim value provided by a specific IDP. Refer to [IDP Configuration](https://github.com/epam/ai-dial/blob/main/docs/tutorials/2.devops/2.auth-and-access-control/3.configure-idps/0.overview.md) to view examples.
* `descriptionKeywords`: A list of keywords describes the toolset, e.g. `code-gen`, `text2image`. 
* `maxRetryAttempts`: A max retry attempts to route a single user request to the toolset's endpoint.
* `author`: The toolset's developer.
* `createdAt`: The date of the toolset creation. 
* `updatedAt`: The date of the last toolset update. 
* `transport`: A transport supported by MCP server. The available options are: `HTTP` or `SSE`.
* `allowedTools`: A list of available tools in the MCP server.              |

**Example:**

```json
"toolsets": {
        "git": {
            "transport": "http",
            "endpoint": "http://git-mcp-server/call",
            "allowed_tools": ["branch", "remote"],
            "description": "Git remote tool set"
        }
    },
```
