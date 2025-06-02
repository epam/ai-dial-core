# IAM Roles for GCP

Google Cloud Storage can be used in GCP deployments where the resources and configurations for DIAL can be stored. Configure the following minimal set of permissions for your bucket for DIAL Core to work properly:

* Storage Bucket Viewer (roles/storage.bucketViewer)
* Storage Object User (roles/storage.objectUser)

> Refer to [Google Cloud Storage](https://cloud.google.com/storage/docs/access-control/iam-roles) to learn about IAM roles.
