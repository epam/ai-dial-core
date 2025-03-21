package com.epam.aidial.core.storage.data;

import lombok.Data;
import org.jclouds.blobstore.domain.MultipartPart;
import org.jclouds.blobstore.domain.MultipartUpload;

import java.util.List;

@Data
public class ResourceUpload {
    private MultipartUpload multipartUpload;
    private List<MultipartPart> parts;
    private String contentType;
    private long contentLength;
    private long updatedAt;
    private long createdAt;
    private String etag;
}
