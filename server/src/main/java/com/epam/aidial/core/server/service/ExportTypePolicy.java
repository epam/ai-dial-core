package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Map;
import javax.annotation.Nullable;

/**
 * The v1 matrix of what an export supports, in one place. Every type that is
 * not listed is unsupported, so a new resource type starts unsupported. Each answer is a statement about v1, not a general
 * policy for a type.
 */
final class ExportTypePolicy {

    /**
     * What the export does with the body of a type, which decides whether its references are followed.
     */
    enum Content {
        /**
         * The body is a Deployment (an Application, a Model, an Interceptor or a ToolSet). It is exported redacted, and its references are
         * read, so the closure follows them.
         */
        EXAMINED,
        /** A schema document: exported redacted, with no references to follow. */
        SCHEMA,
        /** The body is exported exactly as stored, and its references are not examined. */
        AS_STORED,
        /** A File: its bytes are copied from storage as they are stored, never read as JSON, redacted or examined. */
        STREAMED,
        /** A resource of versioned files, of which the files of the pinned current version are copied as they are stored. */
        COMPLEX
    }

    /**
     * @param isRoot         only an Application is accepted as a root for now
     * @param inPlatform     a supported dependency in {@code platform/}
     * @param inPublic       a supported dependency in {@code public/}; a public dependency that is not an Application is never a root
     * @param isDefinitionOnly only the definition is exported, without the endpoint, tokenizer or any other external artifact that it names
     */
    private record Traits(boolean isRoot, boolean inPlatform, boolean inPublic, Content content, boolean isDefinitionOnly) {
    }

    private static final Traits UNSUPPORTED = new Traits(false, false, false, null, false);

    /** One row per supported type; every type that has no row is unsupported. */
    private static final Map<ResourceTypes, Traits> TRAITS = Map.ofEntries(
            Map.entry(ResourceTypes.APPLICATION, new Traits(true, true, true, Content.EXAMINED, false)),
            Map.entry(ResourceTypes.TOOL_SET, new Traits(false, true, true, Content.EXAMINED, false)),
            Map.entry(ResourceTypes.MODEL, new Traits(false, true, false, Content.EXAMINED, true)),
            Map.entry(ResourceTypes.INTERCEPTOR, new Traits(false, true, false, Content.EXAMINED, true)),
            Map.entry(ResourceTypes.APP_TYPE_SCHEMA, new Traits(false, true, false, Content.SCHEMA, false)),
            Map.entry(ResourceTypes.CATALOG_SCHEMA, new Traits(false, true, false, Content.SCHEMA, false)),
            Map.entry(ResourceTypes.FILE, new Traits(false, false, true, Content.STREAMED, false)),
            Map.entry(ResourceTypes.SKILL, new Traits(false, false, true, Content.COMPLEX, false)),
            Map.entry(ResourceTypes.PROMPT, new Traits(false, false, true, Content.AS_STORED, false)));

    private ExportTypePolicy() {
    }

    private static Traits traitsOf(ResourceTypes type) {
        return TRAITS.getOrDefault(type, UNSUPPORTED);
    }

    /**
     * @return the type of the resource, which the export only handles for the types of {@link ResourceTypes}
     */
    static ResourceTypes typeOf(ResourceDescriptor descriptor) {
        return (ResourceTypes) descriptor.getType();
    }

    static boolean isRoot(ResourceTypes type) {
        return traitsOf(type).isRoot();
    }

    /**
     * @param bucketLocation the decrypted bucket location of the dependency; only exactly {@code platform/} and
     *                       {@code public/} are eligible
     */
    static boolean isSupportedDependency(ResourceTypes type, String bucketLocation) {
        Traits traits = traitsOf(type);
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(bucketLocation)) {
            return traits.inPlatform();
        }
        return ResourceDescriptor.PUBLIC_LOCATION.equals(bucketLocation) && traits.inPublic();
    }

    /**
     * The bucket binding of the configuration API ({@link EntityBucketBinding}), checked before a resource is read or probed. The binding is keyed by the URL segment of the
     * type, which for an application type schema is {@code schemas} and not its blob group {@code app_type_schemas}.
     *
     * @param bucketName the bucket as it is named in the URL
     */
    static boolean isBound(ResourceTypes type, String bucketName) {
        if (type == ResourceTypes.SKILL) {
            // EntityBucketBinding has no entry for a complex resource; for the export a Skill is bound to the public bucket only (task 13.3, E6)
            return ResourceDescriptor.PUBLIC_BUCKET.equals(bucketName);
        }
        return EntityBucketBinding.isAllowed(type.urlSegment(), bucketName);
    }

    /**
     * @return what the export does with the body of the type; {@code null} for an unsupported type
     */
    @Nullable
    static Content contentOf(ResourceTypes type) {
        return traitsOf(type).content();
    }

    /**
     * @return whether only the definition of this type is exported, without the endpoint, tokenizer or any other external artifact that
     *         it names, which the registry warns of
     */
    static boolean isDefinitionOnly(ResourceTypes type) {
        return traitsOf(type).isDefinitionOnly();
    }
}
