package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Bucket(String bucket, String appdata) {
}
