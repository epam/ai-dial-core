package com.epam.aidial.core.credentials.service;

/**
 * Enum defining the modes of changes to resource authentication settings.
 *
 * <p>This enum is used to determine how the authentication settings of a ToolSet
 * should be validated and processed based on the type of operation being performed.
 * It covers scenarios ranging from creating a new client to modifying existing settings
 * without requiring client registration.</p>
 *
 * <p><strong>Supported Modes:</strong></p>
 * <ul>
 *   <li>{@link #CREATE_STATIC_CLIENT}: Indicates that static client registration is required, where all client
 *   details (e.g., client ID, client secret, and endpoints) are provided explicitly by the client.</li>
 *
 *   <li>{@link #CREATE_DYNAMIC_CLIENT}: Indicates that dynamic client registration is required, where the authorization
 *   server generates the client details (e.g., client ID and client secret) at runtime using metadata.</li>
 *
 *   <li>{@link #NO_CLIENT_CHANGES}: Indicates updates that modify existing authentication settings for any type
 *   (OAuth, API_KEY, NONE), but do not require client registration.</li>
 * </ul>
 */
public enum ResourceAuthSettingsChangeMode {

    /**
     * Static client registration mode.
     *
     * <p>This mode applies when a client is being registered with explicit client details
     * (e.g., client ID, client secret). The client details must be provided as part of
     * the `ResourceAuthSettings`. Static registration is typically used when the client
     * credentials are pre-provisioned or manually configured.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Creating a new ToolSet with OAuth authentication and static client credentials.</li>
     *   <li>Updating a ToolSet's authentication type to OAuth with pre-defined client credentials.</li>
     * </ul>
     */
    CREATE_STATIC_CLIENT,

    /**
     * Dynamic client registration mode.
     *
     * <p>This mode applies when a client is being registered dynamically with the help
     * of metadata. Client details such as client ID and client secret are generated
     * dynamically by the authorization server at runtime.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Creating a new ToolSet with OAuth authentication where client details
     *   are not pre-configured.</li>
     *   <li>Changing a ToolSet's authentication type from a non-OAuth type (e.g., API_KEY, NONE)
     *   to OAuth using dynamic registration.</li>
     * </ul>
     */
    CREATE_DYNAMIC_CLIENT,

    /**
     * Updates existing authentication settings without requiring new client registration.
     *
     * <p>This mode applies to updates for any authentication type (OAuth, API_KEY, NONE),
     * where the settings are modified but no registration is needed for the client.</p>
     *
     * <p>Changes may include updates to fields such as client ID, client secret, endpoints,
     * scopes, or API_KEY headers, depending on the authentication type.</p>
     *
     * <p><strong>Use Cases:</strong></p>
     * <ul>
     *   <li>Updating existing OAuth client settings (e.g., changing client ID, client secret, endpoints, or scopes)
     *   without requiring a new registration.</li>
     *   <li>Updating API_KEY authentication settings (e.g., modifying the API key header).</li>
     *   <li>Preserving existing auto-generated fields (e.g., code challenges, code verifiers) during updates,
     *   unless explicitly overridden.</li>
     * </ul>
     */
    NO_CLIENT_CHANGES
}
