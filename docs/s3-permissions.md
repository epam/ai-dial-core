# AWS S3 Permissions

Amazon S3 can be used in AWS deployments where the resources and configurations for DIAL can be stored. Configure the following permissions for your S3 bucket for DIAL Core to work properly: 

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
