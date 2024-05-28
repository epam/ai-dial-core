# DIAL CORE

## Overview

HTTP Proxy provides unified API to different chat completion and embedding models, assistants and applications.
Written in Java 17 and built on top of [Eclipse Vert.x](https://vertx.io/).

## Build

Build the project with Gradle and Java 17:

```
./gradlew build
```

## Run

Run the project with Gradle:

```
./gradlew run
```

Or run com.epam.aidial.core.AIDial class from your favorite IDE.

## Configuration

### Static settings

Static settings are used on startup and cannot be changed while application is running. See [example](sample/aidial.settings.json). Priority order:

* Environment variables with extra "aidial." prefix. E.g. "aidial.server.port", "aidial.config.files".
* File specified in "AIDIAL_SETTINGS" environment variable.
* Default resource file: src/main/resources/aidial.settings.json.

| Setting     | Default           | Required | Description
|--------------------------------------------|-------------------|----------|-----------------------------------------------------------------------------------------------------------
| config.files| aidial.config.json | No       |List of paths to dynamic settings. See [example](sample/aidial.config.json) of the file with dynamic settings.
| config.reload                              | 60000             | No       |Config reload interval in milliseconds.
| identityProviders                          | -                 | Yes      |Map of identity providers. **Note**. At least one identity provider must be provided. See [examples](sample/aidial.settings.json) of available providers.
| identityProviders.*.jwksUrl                | -                 | Optional |Url to jwks provider. **Required** if `disabledVerifyJwt` is set to `false`. *Note*. Either `jwksUrl` or `userInfoEndpoint` must be provided.
| identityProviders.*.userInfoEndpoint       | -                 | Optional |Url to user info endpoint. *Note*. Either `jwksUrl` or `userInfoEndpoint` must be provided or `disableJwtVerification` is unset. See [Google example](sample/aidial.settings.json). 
| identityProviders.*.rolePath               | -                 | Yes      |Path to the claim user roles in JWT token or user info response, e.g. `resource_access.chatbot-ui.roles` or just `roles`. 
| identityProviders.*.loggingKey             | -                 | No       |User information to search in claims of JWT token. `email` or `sub` should be sufficient in most cases. **Note**. `email` might be unavailable for some IdPs. Please check your IdP documentation in this case.
| identityProviders.*.loggingSalt            | -                 | No       |Salt to hash user information for logging.
| identityProviders.*.positiveCacheExpirationMs        | 600000            | No       | How long to retain JWKS response in the cache in case of successfull response.
| identityProviders.*.negativeCacheExpirationMs    | 10000             | No       |How long to retain JWKS response in the cache in case of failed response.
| identityProviders.*.issuerPattern          | -                 | No       |Regexp to match the claim "iss" to identity provider
| identityProviders.*.disableJwtVerification | false             | No       |The flag disables JWT verification. *Note*. `userInfoEndpoint` must be unset if the flag is set to `true`.
| vertx.*     | -                 | No       |Vertx settings. See more details (here)[https://vertx.io/docs/apidocs/io/vertx/core/VertxOptions.html].
| server.*    | -                 | No       |Vertx HTTP server settings for incoming requests.
| client.*    | -                 | No       |Vertx HTTP client settings for outbound requests.
| storage.provider                           | filesystem                  | Yes      |Specifies blob storage provider. Supported providers: s3, aws-s3, azureblob, google-cloud-storage, filesystem. See examples in the sections below.
| storage.endpoint                           | -                 | No       |Optional. Specifies endpoint url for s3 compatible storages. **Note**. The setting might be required. That depends on a concrete provider.
| storage.identity                           | -                 | Optional |Blob storage access key. Can be optional for filesystem, aws-s3, google-cloud-storage providers
| storage.credential                         | -                 | Optional |Blob storage secret key. Can be optional for filesystem, aws-s3, google-cloud-storage providers
| storage.bucket                             | -                 | No       |Blob storage bucket
| storage.overrides.*                        | -                 | No       |Key-value pairs to override storage settings. `*` might be any specific blob storage setting to be overridden. See examples in the sections below. 
| storage.createBucket                       | false             | No       |Indicates whether bucket should be created on start-up
| storage.prefix                             | -                 | No       |Base prefix for all stored resources. The purpose to use the same bucket for different environments, e.g. dev, prod, pre-prod. Must not contain path separators or any invalid chars
| encryption.password                        | -                 | No       |Password used for AES encryption
| encryption.salt                            | -                 | No       |Salt used for AES encryption. The value should be random generated string.
| resources.maxSize                          | 1048576           | No       |Max allowed size in bytes for a resource
| resources.syncPeriod                       | 60000             | No       |Period in milliseconds, how frequently check for resources to sync
| resources.syncDelay                        | 120000            | No       |Delay in milliseconds for a resource to be written back in object storage after last modification
| resources.syncBatch                        | 4096              | No       |How many resources to sync in one go
| resources.cacheExpiration                  | 300000            | No       |Expiration in milliseconds for synced resources in Redis
| resources.compressionMinSize               | 256               | No       |Compress a resource with gzip if its size in bytes more or equal to this value
| redis.singleServerConfig.address           | -                 | Yes      |Redis single server addresses, e.g. "redis://host:port". Either `singleServerConfig` or `clusterServersConfig` must be provided. 
| redis.clusterServersConfig.nodeAddresses   | -                 | Yes      |Json array with Redis cluster server addresses, e.g. ["redis://host1:port1","redis://host2:port2"]. Either `singleServerConfig` or `clusterServersConfig` must be provided.
| invitations.ttlInSeconds                   | 259200            | No       |Invitation time to live in seconds
| access.admin.rules                         | -                 | No       |Matches claims from identity providers with the rules to figure out whether a user is allowed to perform admin actions, like deleting any resource or approving a publication. Example: [{"source": "roles", "function": "EQUAL", "targets": ["admin"]}]. If roles contain "admin, the actions are allowed.

### Storage requirements

DIAL Core stores user data in the following storages:
* **Blob Storage** keeps permanent data.
* **Redis** keeps volatile in-memory data for fast access.

### AWS S3 Blob Store

There are two types of credential providers supported:
- User credentials. You can create a service principle and authenticate using its secret from Azure console
- Temporary credentials with [IAM roles for service accounts](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html)

#### User credentials

You should set `storage.credential` to Secret Access Key  and `storage.identity` -  Access Key ID.

#### Temporary credentials

You should follow [instructions](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html) to setup your pod in AWS EKS.
`storage.credential` and `storage.identity` must be unset.

### Google Cloud Storage

There are two types of credential providers supported:
 - User credentials. You can create a service account and authenticate using its private key obtained from Developer console
 - Temporary credentials. Application default credentials (ADC)

#### User credentials

You should set `storage.credential` to a path to the private key JSON file and `storage.identity` must be unset.
See example below:
```
{
  "type": "service_account",
  "project_id": "<your_project_id>",
  "private_key_id": "<your_project_key_id>",
  "private_key": "-----BEGIN PRIVATE KEY-----\n<your_private_key>\n-----END PRIVATE KEY-----\n",
  "client_email": "gcp-dial-core@<your_project_id>.iam.gserviceaccount.com",
  "client_id": "<client_id>",
  "auth_uri": "https://accounts.google.com/o/oauth2/auth",
  "token_uri": "https://oauth2.googleapis.com/token",
  "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
  "client_x509_cert_url": "https://www.googleapis.com/robot/v1/metadata/x509/gcp-dial-core.iam.gserviceaccount.com",
  "universe_domain": "googleapis.com"
}
```
Otherwise `storage.credential` is a private key in PEM format and `storage.identity` is a client email address.

#### Temporary credentials

You should follow [instructions](https://cloud.google.com/kubernetes-engine/docs/concepts/workload-identity) to setup your pod in GKE.
`storage.credential` and `storage.identity` must be unset.
JClouds property `jclouds.oauth.credential-type` should be set `bearerTokenCredentials`, e.g.

```
{
  "storage": {
    "overrides": {
      "jclouds.oauth.credential-type": "bearerTokenCredentials"
    }
  }
}
```

### Azure Blob Store

There are two types of credential providers supported:
- User credentials. You can create a service principle and authenticate using its secret from Azure console
- Temporary credentials with Azure AD Workload Identity

#### User credentials

You should set `storage.credential` to service principle secret and `storage.identity` - service principle ID.

#### Temporary credentials

You should follow [instructions](https://azure.github.io/azure-workload-identity/docs/) to setup your pod in Azure k8s.
`storage.credential` and `storage.identity` must be unset.

The properties to be overridden are below:

```
{
  "storage": {
    "endpoint": "https://<Azure Blob storage account>.blob.core.windows.net"
    "overrides": {
      "jclouds.azureblob.auth": "azureAd",
      "jclouds.oauth.credential-type": "bearerTokenCredentials"
    }
  }
}
```

### Redis
The Redis can be used as a cache with volatile-* eviction policies:
```
maxmemory 4G
maxmemory-policy volatile-lfu
```

Note: Redis will be strictly required in the upcoming releases 0.8+.

### Dynamic settings

Dynamic settings are stored in JSON files, specified via "config.files" static setting, and reloaded at interval,
specified via "config.reload" static setting. See [example](sample/aidial.config.json).
Dynamic settings include:

| Parameter                       | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| routes                          | Path(s) for specific upstream routing or to respond with a configured body.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| addons                          | A list of deployed AI DIAL Addons and their parameters:<br />`<addon_name>`: Unique addon name.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| addons.<addon_name>             | `endpoint`: AI DIAL Addon API for chat completions.<br />`iconUrl`: Icon path for the AI DIAL addon on UI.<br />`description`: Brief AI DIAL addon description.<br />`displayName`: AI DIAL addon name on UI.<br />`inputAttachmentTypes`: A list of allowed MIME types for the input attachments.<br />`maxInputAttachments`: Maximum number of input attachments (default is zero when `inputAttachmentTypes` is unset, otherwise, infinity) <br/> `forwardAuthToken`: If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the addon.                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| assistant                          | A list of deployed AI DIAL Assistants and their parameters:<br />`<assistant_name>`: Unique assistan name.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| assistant.endpoint                          | Assistant main endpoint                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| assistant.assistants.<asistant_name>             | `iconUrl`: Icon path for the AI DIAL assistant on UI.<br />`description`: Brief AI DIAL assistant description.<br />`displayName`: AI DIAL assistant name on UI.<br />`inputAttachmentTypes`: A list of allowed MIME types for the input attachments.<br />`maxInputAttachments`: Maximum number of input attachments (default is zero when `inputAttachmentTypes` is unset, otherwise, infinity) <br/> `forwardAuthToken`: If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the assistant.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| applications                    | A list of deployed AI DIAL Applications and their parameters:<br />`<application_name>`: Unique application name.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| applications.<application_name> | `endpoint`: AI DIAL Application API for chat completions.<br />`iconUrl`: Icon path for the AI DIAL Application on UI.<br />`description`: Brief AI DIAL Application description.<br />`displayName`: AI DIAL Application name on UI.<br />`inputAttachmentTypes`: A list of allowed MIME types for the input attachments.<br />`maxInputAttachments`: Maximum number of input attachments (default is zero when `inputAttachmentTypes` is unset, otherwise, infinity) <br/> `forwardAuthToken`: If flag is set to `true` forward Http header with authorization token to chat completion endpoint of the application.                                                                                                                                                                                                                                                                                                                                                                                                                          |
| models                          | A list of deployed models and their parameters:<br />`<model_name>`: Unique model name.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| models.<model_name>             | `type`: Model type—`chat` or `embedding`.<br />`iconUrl`: Icon path for the model on UI.<br />`description`: Brief model description.<br />`displayName`: Model name on UI.<br />`displayVersion`: Model version on UI.<br />`endpoint`: Model API for chat completions or embeddings.<br />`tokenizerModel`: Identifies the specific model whose tokenization algorithm exactly matches that of the referenced model. This is typically the name of the earliest-released model in a series of models sharing an identical tokenization algorithm (e.g. `gpt-3.5-turbo-0301`, `gpt-4-0314`, or `gpt-4-1106-vision-preview`). This parameter is essential for DIAL clients that reimplement tokenization algorithms on their side, instead of utilizing the `tokenizeEndpoint` provided by the model.<br />`features`: Model features.<br />`limits`: Model token limits.<br />`pricing`: Model pricing.<br />`upstreams`: Used for load-balancing—request is sent to model endpoint containing X-UPSTREAM-ENDPOINT and X-UPSTREAM-KEY headers. |
| models.<model_name>.limits      | `maxPromptTokens`: maximum number of tokens in a completion request.<br />`maxCompletionTokens`: maximum number of tokens in a completion response.<br />`maxTotalTokens`: maximum number of tokens in completion request and response combined.<br />Typically either `maxTotalTokens` is specified or `maxPromptTokens` and `maxCompletionTokens`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| models.<model_name>.pricing     | `unit`: the pricing units (currently `token` and `char_without_whitespace` are supported).<br />`prompt`: per-unit price for the completion request in USD.<br />`completion`: per-unit price for the completion response in USD.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
| models.<model_name>.features    | `rateEndpoint`: endpoint for rate requests *(exposed by core as `<deployment name>/rate`)*.<br />`tokenizeEndpoint`: endpoint for requests to the model tokenizer *(exposed by core as `<deployment name>/tokenize`)*.<br />`truncatePromptEndpoint`: endpoint for truncating prompt requests *(exposed by core as `<deployment name>/truncate_prompt`)*.<br />`systemPromptSupported`: does the model support system prompt (default is `true`).<br />`toolsSupported`: does the model support tools (default is `false`).<br />`seedSupported`: does the model support `seed` request parameter (default is `false`).<br />`urlAttachmentsSupported`: does the model/application support attachments with URLs (default is `false`).<br />`folderAttachmentsSupported`: does the model/application support folder attachments (default is `false`)                                                                                                                                                                                            |
| models.<model_name>.upstreams   | `endpoint`: Model endpoint.<br />`key`: Your API key.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| keys                            | API Keys parameters:<br />`<core_key>`: Your API key.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| keys.<core_key>                 | `project`: Project name is assigned to this key.<br />`role`: A configured role name to be defined in the section `roles`..                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| roles                           | API key or user roles. Each role may have limits to be associated with applications, models, assistants or addons.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| roles.<role_name>               | `limits`: Limits for models, applications, or assistants.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| roles.<role_name>.limits        | `minute`: Total tokens per minute limit sent to the model, managed via floating window approach for well-distributed rate limiting. If it's not set the default value is unlimited<br />`day`: Total tokens per day limit sent to the model, managed via floating window approach for balanced rate limiting. If it's not set the default value is unlimited                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |

## License

Copyright (C) 2024 EPAM Systems

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

