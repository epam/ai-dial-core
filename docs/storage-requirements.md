# Storage Requirements

AI DIAL Core stores user data in the following storages:
* **Blob Storage** keeps permanent data.
* [Redis](#redis) keeps volatile in-memory data for fast access.

## AWS S3 Blob Store

There are two types of credential providers supported:
- User credentials. You can create a service principle and authenticate using its secret from the Azure console.
- Temporary credentials with [IAM roles for service accounts](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html).

### Required Permissions

Configure the following permissions for your S3 bucket for DIAL Core to work properly: 

```json
{
    "Statement": [
        {
            "Action": [
                "s3:PutObjectAcl",
                "s3:PutObject",
                "s3:ListBucketMultipartUploads",
                "s3:ListBucket",
                "s3:GetObject",
                "s3:GetBucketLocation",
                "s3:DeleteObject",
                "s3:AbortMultipartUpload"
            ],
            "Effect": "Allow",
            "Resource": [
                "arn:aws:s3:::your-dial-core-storage-bucket/*",
                "arn:aws:s3:::your-dial-core-storage-bucket"
            ],
            "Sid": ""
        }
    ],
    "Version": "2012-10-17"
}
```

### User credentials

Set `storage.credential` to Secret Access Key  and `storage.identity` -  Access Key ID.

### Temporary credentials

Follow [instructions](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html) to setup your pod in AWS EKS.
`storage.credential` and `storage.identity` must be unset.

## Google Cloud Storage

There are two types of credential providers supported:
 - User credentials. You can create a service account and authenticate using its private key obtained from the Developer console.
 - Temporary credentials. Application default credentials (ADC).

### Required Permissions

Refer to [GCP IAM Roles](/docs/gcp-iam-roles.md) to see the minimal bucket permissions required by DIAL Core.

### User credentials

Set `storage.credential` to a path to the private key JSON file and `storage.identity` must be unset. Refer to the example below:

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
Otherwise, `storage.credential` is a private key in PEM format and `storage.identity` is a client's email address.

### Temporary credentials

Follow [instructions](https://cloud.google.com/kubernetes-engine/docs/concepts/workload-identity) to setup your pod in GKE.
`storage.credential` and `storage.identity` must be unset.
JClouds property `jclouds.oauth.credential-type` should be set to `bearerTokenCredentials`, refer to the below example.

```
{
  "storage": {
    "overrides": {
      "jclouds.oauth.credential-type": "bearerTokenCredentials"
    }
  }
}
```

## Azure Blob Store

There are two types of credential providers supported:
- User credentials. You can create a service principle and authenticate using its secret from the Azure console.
- Temporary credentials with Azure AD Workload Identity.

### User credentials

Set `storage.credential` to the service principle secret and `storage.identity` - service principle ID.

### Temporary credentials

Follow [instructions](https://azure.github.io/azure-workload-identity/docs/) to setup your pod in Azure k8s.
`storage.credential` and `storage.identity` must be unset.

This example demonstrates the properties to be overridden:

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

## Redis

Redis can be used as a cache with volatile-* eviction policies:
```
maxmemory 4G
maxmemory-policy volatile-lfu
```

> **Note**: Redis will be strictly required in the upcoming releases 0.8+.
