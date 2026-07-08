package com.epam.aidial.core.openapi.annotations;

/**
 * Reusable parameter and response descriptions for OpenAPI annotations.
 */
public final class OpenApiDescriptions {

    public static final String API_VERSION =
            "The API version to use for this request. Follows the `YYYY-MM-DD[-preview]` format.";

    public static final String CACHE_POLICY =
            "Upstream selection policy for prompt-caching deployments (availability-priority or cache-priority).";

    public static final String UPSTREAM_ID = "Pin the request to one configured upstream, matched by upstream id";
    public static final String DEPLOYMENT_NAME = "The name of the deployment.";
    public static final String DEPLOYMENT_ID = "The unique identifier of the deployment.";
    public static final String DEPLOYMENT_IDENTIFIER = "Deployment identifier.";

    /** Reusable metadata listing query parameters. */
    public static final String QUERY_TOKEN = "Pagination token.";
    public static final String QUERY_LIMIT = "Maximum number of items.";
    public static final String QUERY_RECURSIVE = "Whether to traverse recursively.";
    public static final String QUERY_PERMISSIONS = "Include permissions information.";

    public static final String IF_MATCH = "ETag precondition.";
    public static final String IF_NONE_MATCH = "Conditional creation precondition.";
    public static final String APPLICATION_NAME = "The name of the Application.";
    public static final String MODEL_NAME = "The name of the model.";
    public static final String TOOLSET_NAME = "The name of the toolset.";
    public static final String TOOLSET_ID =
            "The target toolset ID. The parameter specifies the unique identifier of the toolset.";

    public static final String BUCKET = "The target bucket.";

    public static final String FILE_PATH =
            "The target file path. The parameter specifies full path to the file, for example: `folder1/folder2/file.png`";

    public static final String CONVERSATION_PATH =
            "The target conversation path. The parameter specifies full path to the conversation, "
                    + "for example: `folder1/folder2/conversation_name`";

    public static final String PROMPT_PATH =
            "The target prompt path. The parameter specifies full path to the prompt, "
                    + "for example: `folder1/folder2/prompt_name`";

    public static final String APPLICATION_PATH =
            "The target application path. The parameter specifies full path to the application, "
                    + "for example: `folder1/folder2/application_name`";

    public static final String APPLICATION_PATH_SAVE =
            "Target application path. The parameter specifies a full path to the application, "
                    + "for example: `folder1/folder2/application_name`";

    public static final String TOOLSET_PATH =
            "The target toolset path. The parameter specifies full path to the toolset, "
                    + "for example: `folder1/folder2/toolset_name`";

    public static final String METADATA_PATH_FILES =
            "The parameter specifies path to the requested directory or file, for example: `folder1/folder2/`. "
                    + "Note, it could be empty if you want to list the root folder.";

    public static final String METADATA_PATH_CONVERSATIONS =
            "The parameter specifies path to the requested directory or a conversation, for example: `folder1/folder2/`. "
                    + "Note, it could be empty if you want to list the root folder.";

    public static final String METADATA_PATH_PROMPTS =
            "The parameter specifies path to a directory or a prompt, for example: `folder1/folder2/`.";

    public static final String METADATA_PATH_APPLICATIONS =
            "The parameter specifies path to the requested directory or application, for example: `folder1/folder2/` "
                    + "or `folder1/application_name/`. Note, it could be empty if you want to list the root folder.";

    public static final String METADATA_PATH_TOOLSETS =
            "The parameter specifies path to a directory or a toolset, for example: `folder1/folder2/`.";

    public static final String METADATA_TOKEN = "The token from the previous request to request next items.";
    public static final String METADATA_LIMIT = "Limit on the number of items in the response.";
    public static final String METADATA_RECURSIVE =
            "If true, returns items recursively without nested folder metadata.";

    public static final String METADATA_PERMISSIONS_APPLICATIONS =
            "If true, returns the permissions applicable to the requestor, indicating what actions they can perform "
                    + "on applications.";

    public static final String METADATA_PERMISSIONS_CONVERSATIONS =
            "If true, returns the permissions applicable to the requestor, indicating what actions they can perform "
                    + "on the conversations.";

    public static final String METADATA_PERMISSIONS_FILES =
            "If true, returns the permissions applicable to the requestor, indicating what actions they can perform "
                    + "on the files.";

    public static final String METADATA_PERMISSIONS_PROMPTS =
            "If true, returns the permissions applicable to the requestor, indicating what actions they can perform "
                    + "on the prompts.";

    public static final String METADATA_PERMISSIONS_TOOLSETS =
            "If true, returns the permissions applicable to the requestor, indicating what actions they can perform "
                    + "on the toolsets.";

    public static final String IF_MATCH_UPLOAD_FILE =
            "The entity tag (ETag) of the file. This is used for conditional requests to ensure that the file is only "
                    + "uploaded if it matches the specified ETag. If the file does not exist, and this header is provided, "
                    + "the request returns 412 Precondition Failed response. If this header is not provided or the value "
                    + "is \"*\", any existing file at the specified path will be overwritten.";

    public static final String IF_NONE_MATCH_UPLOAD_FILE =
            "The entity tag (ETag) used to ensure that the file is only uploaded if it does not already exist. "
                    + "The only supported value is \"*\".";

    public static final String IF_MATCH_DELETE_FILE =
            "The entity tag (ETag) of the file. This is used for conditional requests to ensure that the file is only "
                    + "deleted if it matches the specified ETag.";

    public static final String IF_MATCH_UPLOAD_CONVERSATION =
            "The entity tag (ETag) of the conversation. This is used for conditional requests to ensure that the "
                    + "conversation is only uploaded if it matches the specified ETag. If the conversation does not exist, "
                    + "this header is ignored. If this header is not provided or the value is \"*\", any existing "
                    + "conversation at the specified path will be overwritten.";

    public static final String IF_NONE_MATCH_UPLOAD_CONVERSATION =
            "The entity tag (ETag) used to ensure that the conversation is only uploaded if it does not already exist. "
                    + "The only supported value is \"*\".";

    public static final String IF_MATCH_DELETE_CONVERSATION =
            "The entity tag (ETag) of the conversation. This is used for conditional requests to ensure that the "
                    + "conversation is only deleted if it matches the specified ETag.";

    public static final String IF_MATCH_UPLOAD_PROMPT =
            "The entity tag (ETag) of the prompt. This is used for conditional requests to ensure that the prompt is only "
                    + "uploaded if it matches the specified ETag. If the prompt does not exist, this header is ignored. "
                    + "If this header is not provided or the value is \"*\", any existing prompt at the specified path "
                    + "will be overwritten.";

    public static final String IF_NONE_MATCH_UPLOAD_PROMPT =
            "The entity tag (ETag) used to ensure that the prompt is only uploaded if it does not already exist. "
                    + "The only supported value is \"*\".";

    public static final String IF_MATCH_DELETE_PROMPT =
            "The entity tag (ETag) of the prompt. This is used for conditional requests to ensure that the prompt is only "
                    + "deleted if it matches the specified ETag.";

    public static final String IF_MATCH_UPLOAD_APPLICATION =
            "The entity tag (ETag) of the application. This is used for conditional requests to ensure that the application "
                    + "is only uploaded if it matches the specified ETag. If the application does not exist, this header is "
                    + "ignored. If this header is not provided or the value is \"*\", any existing application at the "
                    + "specified path will be overwritten.";

    public static final String IF_NONE_MATCH_UPLOAD_APPLICATION =
            "The entity tag (ETag) used to ensure that the application is only uploaded if it does not already exist. "
                    + "The only supported value is \"*\".";

    public static final String IF_MATCH_DELETE_APPLICATION =
            "The entity tag (ETag) of the application. This is used for conditional requests to ensure that the application "
                    + "is only deleted if it matches the specified ETag.";

    public static final String IF_MATCH_UPLOAD_TOOLSET =
            "The entity tag (ETag) of the toolset. This is used for conditional requests to ensure that the toolset is only "
                    + "uploaded if it matches the specified ETag. If the toolset does not exist, this header is ignored. "
                    + "If this header is not provided or the value is \"*\", any existing toolset at the specified path "
                    + "will be overwritten.";

    public static final String IF_NONE_MATCH_UPLOAD_TOOLSET =
            "The entity tag (ETag) used to ensure that the toolset is only uploaded if it does not already exist. "
                    + "The only supported value is \"*\".";

    public static final String IF_MATCH_DELETE_TOOLSET =
            "The entity tag (ETag) of the toolset. This is used for conditional requests to ensure that the toolset is only "
                    + "deleted if it matches the specified ETag.";

    public static final String INVITATION_ACCEPT = "Requests with `accept=true` accept an invitation.";
    public static final String SCHEMA_ID = "schema ID of custom application";

    public static final String CLIENT_CHANNEL_ID = "Client channel ID";
    public static final String CLIENT_CHANNEL_ID_RECONNECT =
            "Client channel ID. The client should provide the header in case of reconnect.";

    public static final String INTERFACE_TYPE =
            "Filter deployments by the interface types they support (chat, embedding, mcp, custom_ui, all).";

    public static final String RESPONSE_SUCCESS = "Success";
    public static final String RESPONSE_INVALID_AUTHENTICATION = "Invalid Authentication";
    public static final String RESPONSE_BAD_REQUEST = "Bad request";
    public static final String RESPONSE_UNAUTHORIZED = "Unauthorized";
    public static final String RESPONSE_RATE_LIMIT = "Rate limit reached.";
    public static final String RESPONSE_SERVER_ERROR =
            "The server had an error while processing your request.";
    public static final String RESPONSE_UPSTREAM_ERROR = "Failed to connect to upstream server.";
    public static final String RESPONSE_OVERLOADED =
            "The engine is currently overloaded, please try again later.";
    public static final String RESPONSE_DEPLOYMENT_NOT_FOUND =
            "Not found\n\nReturned either when:\n"
                    + "1. The deployment called `{deployment_name}` doesn't exist. Check the DIAL listing to verify "
                    + "that the deployment does actually exist.\n"
                    + "2. The `api-version` query parameter points to an API version that doesn't exist. This is "
                    + "relevant only for deployments based on Azure OpenAI models.";
    public static final String RESPONSE_PRECONDITION_FAILED = "Precondition Failed - ETag mismatch";
    public static final String RESPONSE_FORBIDDEN = "Forbidden";
    public static final String RESPONSE_NOT_FOUND = "Not found";
    public static final String RESPONSE_CONFLICT = "Conflict";
    public static final String EXTERNAL_SERVICE_APP_ID =
            "The application ID. Can be either a static config application name or a dynamic application path.";

    public static final String EXTERNAL_SERVICE_ID =
            "The external service ID defined in the application's external_services configuration.";

    private OpenApiDescriptions() {
    }
}