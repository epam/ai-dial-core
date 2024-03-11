package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.util.UrlUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ResourceUrl {

    @Getter
    private final String rawUrl;
    private final String[] segments;
    @Getter
    private final boolean folder;

    public boolean startsWith(String segment) {
        return segments.length > 0 && segments[0].equals(segment);
    }

    public String getUrl() {
        StringBuilder builder = new StringBuilder(rawUrl.length());

        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append(BlobStorageUtil.PATH_SEPARATOR);
            }

            builder.append(UrlUtil.encodePath(segments[i]));
        }

        if (folder) {
            builder.append(BlobStorageUtil.PATH_SEPARATOR);
        }

        return builder.toString();
    }

    @Override
    public String toString() {
        return getUrl();
    }

    public static ResourceUrl parse(String url) {
        if (url == null) {
            throw new IllegalArgumentException("url is missing");
        }

        try {
            String[] segments = url.split(BlobStorageUtil.PATH_SEPARATOR);

            for (int i = 0; i < segments.length; i++) {
                String segment = UrlUtil.decodePath(segments[i]);

                if (segment == null || segment.isEmpty() || segment.contains(BlobStorageUtil.PATH_SEPARATOR)) {
                    throw new IllegalArgumentException("Bad segment: " + segment + " in url: " + url);
                }

                segments[i] = segment;
            }

            return new ResourceUrl(url, segments, url.endsWith(BlobStorageUtil.PATH_SEPARATOR));
        } catch (Throwable e) {
            throw new IllegalArgumentException("Bad resource url: " + url);
        }
    }
}