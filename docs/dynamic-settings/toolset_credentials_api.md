# **ToolSet Credentials API Integration**

This document outlines updates for the **ToolSet API** that include modifications to existing APIs, the introduction of new APIs, and an additional operation under the `/v1/toolsets` endpoint pattern. The new feature addition primarily focuses on incorporating the **`auth_settings`** object to enable multiple authentication methods. Additionally, authentication-related business operations such as **signin**, **signout**, and **signin-status** have been added.

---

## **🔑 Key Summary of Changes**

| **API**                | **Type** | **Change**                                                                                                                                                       |
|------------------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Create ToolSet**     | PUT      | Added `auth_settings` to the request body to support authentication configurations (**OAUTH**, **API_KEY**, **NONE**) with static and dynamic client options.    |
| **Get ToolSet**        | GET      | Added `auth_settings` to the response body to expose authentication-related details.                                                                             |
| **ToolSet Signin**     | POST     | New API introduced to handle ToolSet authentication. Supports **OAUTH** and **API_KEY** authentication types.                                                    |
| **ToolSet Signout**    | POST     | New API introduced to handle ToolSet logout. Supports **OAUTH** and **API_KEY** authentication types.                                                            |
| **Signin-Status**      | POST     | New API introduced to handle ToolSet sign in status check. Allows checking login status (`signed_in`, `signed_out` or `failed`) for specific credentials levels. |

---

## **1. Modified APIs**

### **1.1 Create ToolSet (PUT)**

This API allows the creation of a new ToolSet with customizable properties. The new addition to this API is the **`auth_settings`** object, which enables specifying authentication methods for the ToolSet.

---

#### **Request Body**
```json
{
    "endpoint": "https://my-mcp.com/mcp",
    "transport": "HTTP",
    "allowedTools": [
        "tool1",
        "tool2"
    ],
    "auth_settings": {
        "authentication_type": "OAUTH",
        "redirect_uri": "{chat-host}/toolset/sign-in"
    }
}
```

| **Field**                 | **Type**   | **Description**                                                                                   |
|---------------------------|------------|---------------------------------------------------------------------------------------------------|
| `endpoint`                | `string`   | The URL of the microservice associated with the ToolSet.                                         |
| `transport`               | `string`   | The communication protocol (e.g., HTTP) of the ToolSet.                                          |
| `allowedTools`            | `array`    | List of tools authorized to use this ToolSet.                                                    |
| `auth_settings`           | `object`   | Authentication settings for the ToolSet.                                                        |
| `authentication_type`     | `string`   | Type of authentication (**OAUTH**, **API_KEY**, or **NONE**) to be used.                         |

---

#### **Options for `auth_settings`**

##### **1. OAUTH (Client Already Exists)**

The client ID and secret are pre-defined and must be provided.

```json
{
    "auth_settings": {
        "authentication_type": "OAUTH",
        "client_id": "your-client-id",
        "client_secret": "your-client-secret",
        "redirect_uri": "{chat-host}/toolset/sign-in"
    }
}
```

---

##### **2. OAUTH (Dynamic Client Registration)**

The client can be dynamically created without including a `client_id` or `client_secret`.

```json
{
    "auth_settings": {
        "authentication_type": "OAUTH",
        "redirect_uri": "{chat-host}/toolset/sign-in"
    }
}
```

---

##### **3. API_KEY**

This method uses API key-based authentication. You specify the key and header name in the configuration.

```json
{
    "auth_settings": {
        "authentication_type": "API_KEY",
        "api_key_header": "your-api-key-header-name"
    }
}
```

---

##### **4. NONE**

This method indicates that no authentication is required for the ToolSet.

```json
{
    "auth_settings": {
        "authentication_type": "NONE"
    }
}
```

---


### **1.2 Get ToolSet (GET)**

The **Get ToolSet** API retrieves the details of a ToolSet. The new addition to the response is the **`auth_settings`** object, which describes the authentication settings used by the ToolSet.

---

#### **Response Body**
```json
{
    "name": "toolsets/{bucket_id}/{path}",
    "endpoint": "https://my-mcp.com/mcp",
    "forward_auth_token": false,
    "defaults": {},
    "interceptors": [],
    "description_keywords": [],
    "max_retry_attempts": 1,
    "created_at": 1755270361029,
    "updated_at": 1755270361029,
    "dependencies": [],
    "transport": "HTTP",
    "allowed_tools": [
        "tool1",
        "tool2"
    ],
    "auth_settings": {
        "authentication_type": "OAUTH",
        "client_id": "new-client-id",
        "authorization_endpoint": "https://my-mcp.com/authorize",
        "redirect_uri": "{chat-host}/toolset/sign-in"
    }
}
```

| **Field**                     | **Type**     | **Description**                                                                |
|-------------------------------|--------------|--------------------------------------------------------------------------------|
| `auth_settings`               | `object`     | Authentication settings configured for this ToolSet.                           |
| `authentication_type`         | `string`     | Type of authentication: **OAUTH**, **API_KEY**, or **NONE**.                   |
| `client_id`                   | `string`     | (OAUTH only) Pre-defined client ID used during signin flows.                   |
| `authorization_endpoint`      | `string`     | (OAUTH only) URL for performing authorization.                                 |
| `redirect_uri`                | `string`     | (OAUTH only) Redirect URI used during signin flows.                            |

---


## **2. New APIs**

The **ToolSet API** introduces new authentication-related operations under a new **path format**:
```
POST /v1/ops/toolset/{operation}
```

### **Operation Format**
| **Operation**   | **Description**                                                                       |
|-----------------|---------------------------------------------------------------------------------------|
| `signin`        | Handles user authentication for the specified ToolSet.                                |
| `signout`       | Explicitly logs out the user by removing stored credentials for the ToolSet.          |
| `signin-status` | Retrieves the login status of a ToolSet (e.g., `signed_in`, `signed_out`, `failed`).  |

---

### **2.1 ToolSet Signin**

#### **Endpoint**

```
POST /v1/ops/toolset/signin
```

#### **Description**

Authenticates the user for a specified ToolSet using OAUTH or API_KEY.

---

#### **Request Body**

##### **OAUTH**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL",
    "authentication_type": "OAUTH",
    "code": "auth-code"
}
```

##### **API-KEY**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL",
    "authentication_type": "API_KEY",
    "api_key": "your_api_key"
}
```

| **Field**             | **Type**   | **Required**         | **Description**                                                               |
|-----------------------|------------|----------------------|-------------------------------------------------------------------------------|
| `url`                 | `string`   | Yes                  | The ToolSet URL (e.g., `toolsets/{bucket}/{path}`).                           |
| `credentials_level`   | `string`   | Yes                  | The scope of credentials for the ToolSet (`GLOBAL`, `APP`, or `USER`).        |
| `authentication_type` | `string`   | Yes                  | The authentication method used (`OAUTH` or `API_KEY`).                        |
| `code`                | `string`   | Required for OAUTH   | The authorization code used in OAUTH authentication flows.                    |
| `api_key`             | `string`   | Required for API_KEY | The API key value.                                                            |

---


### **2.2 ToolSet Signout**

#### **Endpoint**

```
POST /v1/ops/toolset/signout
```

#### **Description**

Logs the user out from the ToolSet by removing the associated credentials. This operation is for explicitly clearing stored credentials.

---

#### **Request Body**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL",
    "authentication_type": "OAUTH"
}
```

| **Field**             | **Type**   | **Required** | **Description**                                                        |
|-----------------------|------------|--------------|------------------------------------------------------------------------|
| `url`                 | `string`   | Yes          | The ToolSet URL (e.g., `toolsets/{bucket}/{path}`).                    |
| `credentials_level`   | `string`   | Yes          | The scope of credentials for the ToolSet (`GLOBAL`, `APP`, or `USER`). |
| `authentication_type` | `string`   | Yes          | The authentication method used (`OAUTH` or `API_KEY`).                 |

---


### **2.3 Signin-Status**

#### **Endpoint**

```
POST /v1/ops/toolset/signin-status
```

#### **Description**

Checks and returns the user's current login status (`signed_in`, `signed_out`, `failed`) for a given ToolSet.

---

#### **Request Body**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL"
}
```

| **Field**           | **Type**   | **Required** | **Description**                                                |
|---------------------|------------|--------------|----------------------------------------------------------------|
| `url`               | `string`   | Yes          | The ToolSet URL (e.g., `toolsets/{bucket}/{path}`).            |
| `credentials_level` | `string`   | Yes          | Specifies the scope of credentials (`GLOBAL`, `APP`, `USER`).  |

---

#### **Response Body**

##### **If Logged In**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL",
    "status": "signed_in"
}
```

##### **If No Credentials Stored**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL",
    "status": "signed_out"
}
```

##### **If Access Token Refresh Fails**
```json
{
    "url": "toolsets/{bucket}/{path}",
    "credentials_level": "GLOBAL",
    "status": "failed"
}
```

| **Field**           | **Type**   | **Description**                                                                           |
|---------------------|------------|-------------------------------------------------------------------------------------------|
| `url`               | `string`   | The unique identifier for the ToolSet (e.g., `toolsets/{bucket}/{path}`).                 |
| `credentials_level` | `string`   | The scope of credentials checked (`GLOBAL`, `APP`, `USER`).                               |
| `status`            | `string`   | The current login status: `signed_in`, `signed_out` (no stored credentials), or `failed`. |

---

### **Signin-Status: Understanding Status**
| **Status**         | **Description**                                                                                                  | 
|--------------------|------------------------------------------------------------------------------------------------------------------|
| `signed_in`        | The user is signed in, and the access token is valid.                                                            |
| `signed_out`       | No credentials are currently stored for the specified ToolSet.                                                   |
| `failed`           | The access token expired, and the attempt to refresh the token via the refresh token has failed (e.g., expired). |

---

### **Summary of New API Endpoints**

| **Operation**           | **Path**                                  | **Description**                                                                                         |
|-------------------------|-------------------------------------------|---------------------------------------------------------------------------------------------------------|
| **ToolSet Signin**      | `POST /v1/ops/toolset/signin`             | Authenticates a user with the specified ToolSet.                                                        |
| **ToolSet Signout**     | `POST /v1/ops/toolset/signout`            | Logs the user out from the specified ToolSet and removes stored credentials.                            |
| **Signin-Status**       | `POST /v1/ops/toolset/signin-status`      | Checks the user's login status for the specified ToolSet (e.g., `signed_in`, `signed_out`, `failed`).   |

---


## **3. Frontend Instructions: Using the `state` Parameter**

#### **Purpose of the `state` Parameter**
The `state` parameter ensures context is preserved during the OAUTH code flow and securely returns information to the frontend after user authentication.

---

#### **What to Include in `state`**
Include the following data in the `state` parameter:
- **Toolset**: The target Toolset for authentication.
- **Credentials Scope**: (Optional) The scope of credentials (`global`, `app`, or `user`).
- **App Context**: (Optional) Any app-specific identifier for multi-app support.

---

#### **Steps for frontend**

1. **Construct the Authorization URL**:
    - **Retrieve Toolset Settings**: Use the backend-provided `auth_settings` (e.g., `client_id`, `redirect_uri`) for the Toolset.
    - **Add `state`**: Build the **/authorize URL** with a serialized `state` parameter, e.g.:
      ```plaintext
      https://mcp-server.com/authorize?client_id=my-client-id&redirect_uri=https://toolset-redirect.com&state=toolset=my-toolset&scope=global
      ```

2. **Sign-In Button**:
    - Open the constructed **/authorize URL** in a browser (or pop-up) to start the OAUTH flow.

3. **Handle Redirects**:
    - After authentication, the MCP Server redirects the User to the `redirect_uri` with an `auth code` and the same `state` passed initially:
      ```plaintext
      https://toolset-redirect.com?code=auth-code&state=toolset=my-toolset&scope=global
      ```

4. **Process `state`**:
    - Parse the `state` parameter from the redirect URL and forward all extracted values (e.g., Toolset, credentials scope) to the backend (**/sign-in API**).

---

#### **Example Overview**
1. **Auth URL Example**:
   ```plaintext
   https://mcp-server.com/authorize?client_id=my-client-id&redirect_uri=https://toolset.redirect.uri&state=toolset=my-toolset&scope=app
   ```

2. **Redirect Example**:
   ```plaintext
   https://toolset.redirect.uri?code=auth-code&state=toolset=my-toolset&scope=app
   ```

---


## **4. Open Questions**
### **Q1. How should the frontend handle the `scope` parameter during the `/authorize` call? Should it be stored in the backend or managed entirely by the frontend?**

#### **Answer:**

**Recommendation:**
- The `scope` parameter defines the permissions that the frontend requests during the OAUTH `/authorize` call.
- By default, the frontend should dynamically manage and include the `scope` based on its requirements (e.g., `read`, `write`, `profile`). The backend does **not** need to store or send `scope` unless specific permissions need to be enforced.
- If the backend wants to enforce a static `scope`, it can be included in the `auth_settings` object during **ToolSet creation (PUT)** and exposed via the **Get ToolSet (GET)** API. The frontend can then retrieve and use it when constructing the `/authorize` URL.
- For flexibility, let the frontend manage the `scope`.
- Include `scope` in the backend’s `auth_settings` only if certain permissions must always be enforced for a ToolSet.

---

### **Q2. How should the frontend handle the `response_type` parameter during the `/authorize` call? Should it be stored in the backend or managed entirely by the frontend?**

#### **Answer:**

**Recommendation:**
- The `response_type` parameter tells the Authorization Server what type of response the client expects.
- In the Authorization Code Grant Flow the backend API supports, `response_type` must always be `code`. This is a standard requirement and won’t change unless a completely different flow is implemented (e.g., Implicit Flow).
- Since the value `response_type=code` is static in this setup, it should be **hardcoded** in the frontend when constructing the `/authorize` URL. The backend does not need to handle or store this value.
- The frontend should hardcode `response_type=code` and does not need to retrieve this value from the backend.

---