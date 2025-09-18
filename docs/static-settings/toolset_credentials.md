# Static Settings for Toolset Credentials

In static settings, you can configure how toolset credentials are encrypted and managed. This ensures that sensitive information is stored securely.

## toolsets.security

This section contains the security settings for toolsets, including authorization, resource identification, and encryption.

| Setting                                  | Default Value | Required | Description                                                                                                                                                                                                                           |
|------------------------------------------|---------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `toolsets.security.authorizationServers` | -             | No       | Path(s) to the authorization server URLs trusted to issue access tokens for MCP clients.                                                                                                                                              |
| `toolsets.security.resourceSchema`       | https         | No       | Schema of the resource server. This URL schema is used to construct the resource identifier for token validation, as defined in RFC 9728. If not specified, the default value will be applied.                                        |
| `toolsets.security.resourceHost`         | -             | No       | The public, fully-qualified hostname of this resource server (e.g., api.example.com). This is used to construct the resource identifier for token validation per RFC 9728. If not set, the host is derived from the incoming request. |
| `toolsets.security.scopesSupported`      | -             | No       | List of scope values, as defined in OAuth 2.0 [RFC6749], that are used in authorization requests to request access to this protected resource.                                                                                        |

### toolsets.security.kms

This section configures the Key Management Service (KMS) used to encrypt and decrypt the Content Encryption Keys (CEKs). Credentials are encrypted with a generated CEK, and each bucket has a unique CEK. The CEKs themselves are encrypted with a provided KMS (AWS, Azure, or GCP).

| Setting                                     | Default Value | Required | Description                                                                                                                                                                                                                                                                                                                                                                                                              |
|---------------------------------------------|---------------|----------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `toolsets.security.kms.provider`            | -             | Yes      | Specifies the KMS provider. Supported providers: `aws`, `azure`, `gcp`, `unencrypted`.                                                                                                                                                                                                                                                                                                                                   |
| `toolsets.security.kms.keyId`               | -             | No       | Identifies the KMS key to use in the encryption operation. **Note** For `aws` to specify a KMS key, use its key ID, key ARN, alias name, or alias ARN. When using an alias name, prefix it with "alias/". To specify a KMS key in a different Amazon Web Services account, you must use the key ARN or alias ARN.                                                                                                        |
| `toolsets.security.kms.region`              | -             | No       | Geo region where the KMS is located. **Required** if `provider` is set to `aws`.                                                                                                                                                                                                                                                                                                                                         |
| `toolsets.security.kms.encryptionAlgorithm` | -             | No       | Encryption algorithm. **Required** if `provider` is set to `azure`. **Note** Refer to [aws](https://docs.aws.amazon.com/kms/latest/APIReference/API_Encrypt.html#API_Encrypt_RequestSyntax), [azure](https://learn.microsoft.com/en-us/java/api/com.azure.security.keyvault.keys.cryptography.models.keywrapalgorithm) to get the list of supported algorithms for azure. Default value for `aws` is `SYMMETRIC_DEFAULT` |

#### KMS Provider Authentication

*   **AWS KMS:** Authentication uses the [Credential chain](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/credentials-chain.html).
*   **Azure KMS:** Authentication uses [DefaultAzureCredential](https://learn.microsoft.com/en-us/dotnet/api/azure.identity.defaultazurecredential).
*   **GCP KMS:** Authentication uses [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials).

#### toolsets.security.kms.cache

This section configures the caching of CEKs to improve performance.

| Setting                                  | Default Value | Required | Description                                  |
|------------------------------------------|---------------|----------|----------------------------------------------|
| `toolsets.security.kms.cache.enabled`    | true          | No       | The flag determines if CEK cache is enabled. |
| `toolsets.security.kms.cache.maxSize`    | 10000         | No       | Maximum number of cached CEK.                |
| `toolsets.security.kms.cache.expiration` | 600000        | No       | Expiration in milliseconds for cached CEK.   |

### toolsets.security.encryption

This section defines the encryption settings for content encryption operations.

| Setting                                             | Default Value     | Required | Description                                                                                                                                                      |
|-----------------------------------------------------|-------------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------|
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

### AZURE KMS Configuration

```json
{
  "toolsets": {
    "security": {
      "kms": {
        "provider": "azure",
        "keyId": "https://your-key-vault.vault.azure.net/keys/your-key-name",
        "encryptionAlgorithm": "RSA-OAEP-256",
        "cache": {
          "maxSize": 10000,
          "expiration": 300000
        }
      }
    }
  }
}
```

### GCP KMS Configuration

```json
{
  "toolsets": {
    "security": {
      "kms": {
        "provider": "gcp",
        "keyId": "projects/your-gcp-project-id/locations/your-location/keyRings/your-key-ring/cryptoKeys/your-key-name/cryptoKeyVersions/1",
        "cache": {
          "maxSize": 10000,
          "expiration": 300000
        }
      }
    }
  }
}
```