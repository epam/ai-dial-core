package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;

import javax.annotation.Nullable;

/**
 * Tells what a reference value is, before anything is read: an absolute URL, a resource URL, a short name or nothing usable.
 */
final class ReferenceClassifier {

    enum ReferenceForm {
        EXTERNAL_URL, RESOURCE_URL, SHORT_NAME, MALFORMED
    }

    /**
     * @param descriptor set only for {@link ReferenceForm#RESOURCE_URL}
     */
    record ClassifiedReference(ReferenceForm form, @Nullable ResourceDescriptor descriptor) {
    }

    private ReferenceClassifier() {
    }

    /**
     * Classifies a marked value: an absolute URL is external, a resource URL carries its descriptor, another slash-free value is a short
     * name, and anything else, including a blank value, is malformed.
     */
    static ClassifiedReference classify(@Nullable String value, EncryptionService encryptionService) {
        if (value == null || value.isBlank()) {
            return new ClassifiedReference(ReferenceForm.MALFORMED, null);
        }
        if (UrlUtil.isAbsoluteUrl(value)) {
            return new ClassifiedReference(ReferenceForm.EXTERNAL_URL, null);
        }
        if (value.indexOf('/') < 0) {
            return new ClassifiedReference(ReferenceForm.SHORT_NAME, null); // a resource URL has at least two segments
        }
        try {
            if (hasUnsafeSegment(value)) {
                return new ClassifiedReference(ReferenceForm.MALFORMED, null);
            }
            return new ClassifiedReference(ReferenceForm.RESOURCE_URL, ResourceDescriptorFactory.fromAnyUrl(value, encryptionService));
        } catch (RuntimeException e) {
            // not a resource URL; the factory refuses it in several ways: a malformed path (UrlUtil.decodePath rethrows URISyntaxException
            // as RuntimeException), an unknown type, or a bucket that the encryption service cannot decrypt
            return new ClassifiedReference(ReferenceForm.MALFORMED, null);
        }
    }

    /**
     * A path segment that is empty, {@code .} or {@code ..}, raw or decoded (so also {@code %2e%2e}), is never a resource: it is refused
     * before anything is read or an entry name is built. Only a value with a slash is examined, and a single trailing slash, which names a
     * folder, is allowed. A value that cannot be decoded is left to the parser, which refuses it.
     */
    private static boolean hasUnsafeSegment(String value) {
        if (value.indexOf('/') < 0) {
            return false;
        }
        String[] segments = value.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            boolean isTrailing = i == segments.length - 1 && i > 0;
            if (segments[i].isEmpty()) {
                if (isTrailing) {
                    continue;
                }
                return true;
            }
            String decoded;
            try {
                decoded = UrlUtil.decodePath(segments[i]);
            } catch (RuntimeException e) {
                return false;
            }
            for (String part : decoded.split("[/\\\\]", -1)) {
                if (part.equals(".") || part.equals("..")) {
                    return true;
                }
            }
        }
        return false;
    }
}
