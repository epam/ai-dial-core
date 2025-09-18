# Static Settings for Toolset Credentials

In static settings, you can configure how toolset credentials are encrypted and managed. This ensures that sensitive information is stored securely.

## Credential Storage and Encryption Flow

This section details where credentials and their corresponding encryption keys are stored and the process used to secure them.

### Storage Locations

All credentials and keys are persisted in a "parallel branch of folders" relative to where the toolsets are stored.

*   **Credentials**: Stored in a `credentials` folder.
    *   **Path Structure**: `<bucket-path>/credentials/<resource-id>`
    *   The `<resource-id>` is derived from the toolset path: `/toolsets/<bucket-id>/<toolset-path>`
    *   **Examples**:
        *   `/public/credentials/<resource-id>`
        *   `/users/<user-id>/credentials/<resource-id>`

*   **Content Encryption Keys (CEKs)**: Stored in an `encryption_keys` folder.
    *   A unique CEK is created per bucket and is stored inside the `encryption_keys` folder within that specific bucket.

### Encryption Process

A two-tiered encryption model ensures robust security:

1.  Each credential is encrypted using a **Content Encryption Key (CEK)**.
2.  The CEK (which is unique to each bucket) is itself stored in an encrypted format. It is encrypted and decrypted using a master key from a configured Key Management Service (KMS).

This means the KMS provider (like AWS, Azure, or GCP) protects the CEK, and the CEK protects the actual credential.

## toolsets.security

This section outlines the security settings for toolsets, specifically for configuring the OAuth 2.0 Protected Resource endpoint as defined by RFC 9728.
These settings enable clients, such as those using the Model Context Protocol (MCP), to securely connect to the toolsets using OAuth 2.0 for authorization.
By providing this metadata, a toolset can declare its security capabilities and point clients to the trusted authorization servers.

| Setting                                  | Default Value | Required | Description                                                                                                                                                                                                                                                                    |
|:-----------------------------------------|:--------------|:---------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `toolsets.security.authorizationServers` | -             | No       | Specifies the URL(s) of the authorization servers that are trusted to issue access tokens for MCP clients. This is a key piece of metadata for clients to initiate the OAuth flow.                                                                                             |
| `toolsets.security.resourceSchema`       | https         | No       | The URL schema used to build the resource identifier for token validation, as specified in RFC 9728. If not provided, the default value of "https" will be used.                                                                                                               |
| `toolsets.security.resourceHost`         | -             | No       | The publicly accessible, fully-qualified hostname of this resource server (e.g., `api.example.com`). This is used to construct the resource identifier for token validation in accordance with RFC 9728. If this is not set, the host is determined from the incoming request. |
| `toolsets.security.scopesSupported`      | -             | No       | A list of scope values, as defined in OAuth 2.0 [RFC6749], that this protected resource supports. This allows clients to request specific levels of access during the authorization process.                                                                                   |

### toolsets.security.kms

This section configures the Key Management Service (KMS) used to encrypt and decrypt Content Encryption Keys (CEKs). As detailed in the encryption process, each bucket has a unique CEK for encrypting credentials. This CEK is, in turn, protected by the configured KMS provider.

| Setting                                     | Default Value | Required | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|:--------------------------------------------|:--------------|:---------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `toolsets.security.kms.provider`            | -             | Yes      | Specifies the KMS provider. Supported providers: `aws`, `azure`, `gcp`, `unencrypted`.                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `toolsets.security.kms.keyId`               | -             | No       | Identifies the KMS key for the encryption operation. <br> **For AWS:** Use the key ID, key ARN, alias name, or alias ARN. When using an alias, prefix it with "alias/". To use a key from a different AWS account, you must provide the key ARN or alias ARN. <br> **For GCP:** Use the resource name of the CryptoKey or CryptoKeyVersion. If a CryptoKey is specified, its primary version will be used. The format is `projects/<project_id>/locations/<location>/keyRings/<key_ring_name>/cryptoKeys/<key_name>`.           |
| `toolsets.security.kms.region`              | -             | No       | The geographic region where the KMS is located. **Required** if the `provider` is set to `aws`.                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `toolsets.security.kms.encryptionAlgorithm` | -             | No       | The encryption algorithm to be used. <br> **Required** if the `provider` is `azure`. For a list of supported algorithms, refer to the [Azure Key Vault documentation](https://learn.microsoft.com/en-us/rest/api/keyvault/keys/wrap-key/wrap-key). <br> **For AWS:** The default is `SYMMETRIC_DEFAULT`. For asymmetric keys, it is recommended to use `RSAES_OAEP_SHA_256`. For more details, see the [AWS KMS documentation](https://docs.aws.amazon.com/kms/latest/APIReference/API_Encrypt.html#API_Encrypt_RequestSyntax). |
#### KMS Provider Authentication

*   **AWS KMS:** Authentication uses the [Credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).
*   **Azure KMS:** Authentication uses [DefaultAzureCredential](https://learn.microsoft.com/en-us/dotnet/api/azure.identity.defaultazurecredential).
*   **GCP KMS:** Authentication uses [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials).

#### toolsets.security.kms.cache

This section configures the caching of CEKs to improve performance.

| Setting                                  | Default Value | Required | Description                                  |
|:-----------------------------------------|:--------------|:---------|:---------------------------------------------|
| `toolsets.security.kms.cache.enabled`    | true          | No       | The flag determines if CEK cache is enabled. |
| `toolsets.security.kms.cache.maxSize`    | 10000         | No       | Maximum number of cached CEK.                |
| `toolsets.security.kms.cache.expiration` | 600000        | No       | Expiration in milliseconds for cached CEK.   |

### toolsets.security.encryption

This section defines the settings for the Content Encryption Key (CEK) used to encrypt the actual credentials.

| Setting                                             | Default Value     | Required | Description                                                                                                                                                      |
|:----------------------------------------------------|:------------------|:---------|:-----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `toolsets.security.encryption.algorithm`            | AES               | No       | The encryption algorithm to use for content encryption operations. Commonly "AES", but may be changed to support other algorithms supported by the JCE provider. |
| `toolsets.security.encryption.keySize`              | 256               | No       | Key size in bits for the encryption algorithm. For AES, valid values are 128, 192, or 256, depending on the algorithm and provider policy.                       |
| `toolsets.security.encryption.cipherTransformation` | AES/GCM/NoPadding | No       | The cipher transformation specifying the algorithm, mode, and padding (e.g., "AES/GCM/NoPadding"). Must be compatible with the selected algorithm.               |
| `toolsets.security.encryption.ivLengthBytes`        | 12                | No       | Length of the initialization vector (IV) in bytes. For AES-GCM, 12 bytes (96 bits) is recommended by NIST.                                                       |
| `toolsets.security.encryption.gcmTagLengthBits`     | 128               | No       | Length of the authentication tag in bits when using GCM mode. NIST recommends 128 bits for maximum integrity protection.                                         |

## Configuration Examples

### AWS KMS Configuration
```json
{
  "toolsets": {
    "security": {
      "authorizationServers": "https://auth.test.com",
      "resourceSchema": "https",
      "resourceHost": "test.com",
      "scopesSupported": [
        "test_scope"
      ],
      "encryption": {
        "algorithm": "AES",
        "keySize": 256,
        "cipherTransformation": "AES/GCM/NoPadding",
        "ivLengthBytes": 12,
        "gcmTagLengthBits": 128
      },
      "kms": {
        "provider": "aws",
        "keyId": "arn:aws:kms:us-east-1:123456789012:key/your-key-id",
        "region": "us-east-1",
        "cache": {
          "maxSize": 10000,
          "expiration": 300000
        }
      }
    }
  }
}
```
