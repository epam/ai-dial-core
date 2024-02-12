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

Static settings are used on startup and cannot be changed while application is running. Priority order:

* Environment variables with extra "aidial." prefix. E.g. "aidial.server.port", "aidial.config.files".
* File specified in "AIDIAL_SETTINGS" environment variable.
* Default resource file: src/main/resources/aidial.settings.json.

| Setting                                    | Default            | Description                                                                                                       
|--------------------------------------------|--------------------|-------------------------------------------------------------------------------------------------------------------
| config.files                               | aidial.config.json | Config files with parts of the whole config.                                                                      
| config.reload                              | 60000              | Config reload interval in milliseconds.                                                                           
| identityProviders                          | -                  | List of identity providers. **Note**. At least one identity provider must be provided.                            
| identityProviders.*.jwksUrl                | -                  | Url to jwks provider. **Required** if `disabledVerifyJwt` is set to `false`                                       
| identityProviders.*.rolePath               | -                  | Path to the claim user roles in JWT token, e.g. `resource_access.chatbot-ui.roles` or just `roles`. **Required**. 
| identityProviders.*.loggingKey             | -                  | User information to search in claims of JWT token.                                                                
| identityProviders.*.loggingSalt            | -                  | Salt to hash user information for logging.                                                                        
| identityProviders.*.cacheSize              | 10                 | How many JWT tokens to cache.                                                                                     
| identityProviders.*.cacheExpiration        | 10                 | How long to retain JWT token in cache.                                                                            
| identityProviders.*.cacheExpirationUnit    | MINUTES            | Unit of cache expiration.                                                                                         
| identityProviders.*.issuerPattern          | -                  | Regexp to match the claim "iss" to identity provider                                                              
| identityProviders.*.disableJwtVerification | false              | The flag disables JWT verification                                                                                
| vertx.*                                    | -                  | Vertx settings.                                                                                                   
| server.*                                   | -                  | Vertx HTTP server settings for incoming requests.                                                                 
| client.*                                   | -                  | Vertx HTTP client settings for outbound requests.                                                                 
| storage.provider                           | -                  | Specifies blob storage provider. Supported providers: s3, aws-s3, azureblob, google-cloud-storage                 
| storage.endpoint                           | -                  | Optional. Specifies endpoint url for s3 compatible storages                                                       
| storage.identity                           | -                  | Blob storage access key                                                                                           
| storage.credential                         | -                  | Blob storage secret key                                                                                           
| storage.bucket                             | -                  | Blob storage bucket  
| storage.overrides.*                        | -                  | Key-value pairs to override storage settings
| storage.createBucket                       | false              | Indicates whether bucket should be created on start-up                                                            
| encryption.password                        | -                  | Password used for AES encryption                                                                                  
| encryption.salt                            | -                  | Salt used for AES encryption                                                                                      
| resources.maxSize                          | 1048576            | Max allowed size in bytes for a resource
| resources.syncPeriod                       | 60000              | Period in milliseconds, how frequently check for resources to sync
| resources.syncDelay                        | 120000             | Delay in milliseconds for a resource to be written back in object storage after last modification
| resources.syncBatch                        | 4096               | How many resources to sync in one go
| resources.cacheExpiration                  | 300000             | Expiration in milliseconds for synced resources in Redis
| resources.compressionMinSize               | 256                | Compress a resource with gzip if its size in bytes more or equal to this value
| redis.singleServerConfig.address           | -                  | Redis single server addresses, e.g. "redis://host:port"                                                                                        
| redis.clusterServersConfig.nodeAddresses   | -                  | Json array with Redis cluster server addresses, e.g. ["redis://host1:port1","redis://host2:port2"]
| invitations.ttlInSeconds                   | 259200             | Invitation time to live in seconds

### Redis
The Redis can be used as a cache with volatile-* eviction policies:
```
maxmemory 4G
maxmemory-policy volatile-lfu
```

Note: Redis will be strictly required in the upcoming releases 0.8+.

### Dynamic settings

Dynamic settings are stored in JSON files, specified via "config.files" static setting, and reloaded at interval,
specified via "config.reload" static setting.
Dynamic settings include:

* Models
* Assistant
* Applications
* Access Keys
* Access Permissions
* Rate Limits

## License

Copyright (C) 2023 EPAM Systems

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

