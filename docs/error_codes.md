## HTTP error codes with descriptions 

#### 400 BAD REQUEST

DIAL Core server cannot or will not process the request due to something that is perceived to be a client error e.g.:
 - Resource URL is malformed
 - Both API key and Authorization headers are provided
 - Folder can't be downloaded

#### 401 UNAUTHORIZED

User credentials are not valid:
 - Bad Authorization header
 - Unknown API key

#### 403 FORBIDDEN

User is authenticated by Core but doesn't have insufficient permissions to a resource or action such that:
- lack of privileges for access to model, application
- admin can approve publications only
- access to attached files in chat completion request

#### 404 NOT FOUND

The user request has been authenticated and authorized but DIAL Core server can't find requested resource such that:
- application, model
- file
- conversation, prompt

#### 405 METHOD NOT ALLOWED

DIAL Core server supports HTTP methods:
- GET
- DELETE
- POST
- PUT
- HEAD
- OPTIONS

#### 409 CONFLICT

The status code indicates a request conflict with the current state of the target resource:

 - Application must be stopped before moving to a different location
 - Application must be stopped before deployment
 - Application must be started before undeployment
 - Application must be stopped before deleting

#### 412 PRECONDITION FAILED

The status code indicates that access to the target resource was denied. This happens with conditional requests on methods other than GET or HEAD when the condition defined by the If-Unmodified-Since or If-Match headers is not fulfilled.
In that case, the request (usually an upload or a modification of a resource) cannot be made and this error response is sent back.

- Concurrent resource modification

#### 413 CONTENT TOO LARGE

The status code indicates that the request entity was larger than limits defined by server:
- `maxUploadedFileSize` defined in storage settings if content type is `multipart/form-data`. The default value is 512 Mb.
- `maxSize` defined in resource settings. The default value is 67 Mb

#### 415 UNSUPPORTED_MEDIA_TYPE

The status code indicates that the server refused to accept the request because the message content format is not supported.
Supported content type is `application/json` for chat completion requests only.

#### 422 UNPROCESSABLE ENTITY

The server failed to receive request body.

#### 429 TOO MANY REQUESTS

The status code indicates the client has sent too many requests in a given amount of time. This mechanism of asking the client to slow down the rate of requests is commonly called "rate limiting".
A Retry-After header may be included to this response to indicate how long a client should wait before making the request again.

That usually happens on chat completion or router requests indicating that user exceeds limits on tokens, requests per specific interval such minute, hour or day.

#### 500 INTERNAL SERVER ERROR

The status code indicates that the server encountered an unexpected condition that prevented it from fulfilling the request. This error is a generic "catch-all" response to server issues, indicating that the server cannot find a more appropriate 5XX error to respond with.

#### 502 BAD GATEWAY
The status code indicates that a server was acting as a gateway or proxy and that it received an invalid response from the upstream server.

 - Upstream server is not available on chat completion request
 - Upstream server is not available behind a route
 - The max number of retry attempts are exceeded to fulfill chat completion request

#### 503 SERVICE UNAVAILABLE
The status code indicates that the server is not ready to handle the request.

Common causes are that a server is down for maintenance or overloaded. During maintenance, server administrators may temporarily route all traffic to a 503 page, or this may happen automatically during software updates.
In overload cases, some server-side applications will reject requests with a 503 status when resource thresholds like memory, CPU, or connection pool limits are met.

- All retries to upstream servers are returned with 429 error
- The application controller is not available
- Code interpreter is not available

#### 504 GATEWAY TIMEOUT

The status code indicates that the server, while acting as a gateway or proxy, did not get a response in time from the upstream server in order to complete the request.

- Upstream server didn't respond in time on chat completion request

#### 505 HTTP VERSION NOT SUPPORTED

DIAL Core supports HTTP 1.1 version only.
