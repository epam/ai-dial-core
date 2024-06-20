package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record ResourceLink(String url) {
}
