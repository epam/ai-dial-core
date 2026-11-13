package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

/** The v1 matrix of what an export supports. A type that is not listed is unsupported, so a new resource type starts unsupported. */
final class ExportTypePolicy {

    /** What the export does with the body of a type, which decides whether its references are followed. */
    enum Content {
        /** A Deployment: exported as the projection of its entity; its references are followed. */
        EXAMINED,
        /** A schema document, exported without the upstream keys of an application type schema. */
        SCHEMA,
        /** Exported exactly as stored; its references are not examined. */
        AS_STORED,
        /** A File: copied as stored, never read as JSON. */
        STREAMED,
        /** Versioned files: those of the pinned current version are copied as stored. */
        COMPLEX
    }

    /**
     * @param redaction removes the secrets from the projected tree; the keys of a model and an interceptor are WRITE_ONLY, so theirs holds none
     */
    private record EntityTraits(Class<? extends Deployment> entityClass, Function<Config, Map<String, ? extends Deployment>> entities,
                                UnaryOperator<ObjectNode> redaction) {
    }

    /**
     * @param isRoot only an Application is accepted as a root for now
     * @param entity {@code null} unless the content is {@link Content#EXAMINED}
     */
    private record Traits(boolean isRoot, Content content, @Nullable EntityTraits entity) {
    }

    private static final Traits UNSUPPORTED = new Traits(false, null, null);

    private static final EntityTraits APPLICATION_ENTITY = new EntityTraits(Application.class, Config::getApplications,
            tree -> ConfigEntityRedactor.redactExternalServiceSecrets(tree, true));
    private static final EntityTraits TOOL_SET_ENTITY = new EntityTraits(ToolSet.class, Config::getToolsets,
            tree -> ConfigEntityRedactor.redactAuthSettingsSecrets(tree, true));
    private static final EntityTraits MODEL_ENTITY = new EntityTraits(Model.class, Config::getModels, UnaryOperator.identity());
    private static final EntityTraits INTERCEPTOR_ENTITY = new EntityTraits(Interceptor.class, Config::getInterceptors, UnaryOperator.identity());

    private static final Map<ResourceTypes, Traits> TRAITS = Map.ofEntries(
            Map.entry(ResourceTypes.APPLICATION, new Traits(true, Content.EXAMINED, APPLICATION_ENTITY)),
            Map.entry(ResourceTypes.TOOL_SET, new Traits(false, Content.EXAMINED, TOOL_SET_ENTITY)),
            Map.entry(ResourceTypes.MODEL, new Traits(false, Content.EXAMINED, MODEL_ENTITY)),
            Map.entry(ResourceTypes.INTERCEPTOR, new Traits(false, Content.EXAMINED, INTERCEPTOR_ENTITY)),
            Map.entry(ResourceTypes.APP_TYPE_SCHEMA, new Traits(false, Content.SCHEMA, null)),
            Map.entry(ResourceTypes.CATALOG_SCHEMA, new Traits(false, Content.SCHEMA, null)),
            Map.entry(ResourceTypes.FILE, new Traits(false, Content.STREAMED, null)),
            Map.entry(ResourceTypes.SKILL, new Traits(false, Content.COMPLEX, null)),
            Map.entry(ResourceTypes.PROMPT, new Traits(false, Content.AS_STORED, null)));

    private ExportTypePolicy() {
    }

    private static Traits resolveTraits(ResourceTypes type) {
        return TRAITS.getOrDefault(type, UNSUPPORTED);
    }

    static ResourceTypes resolveType(ResourceDescriptor descriptor) {
        return (ResourceTypes) descriptor.getType();
    }

    static boolean isRoot(ResourceTypes type) {
        return resolveTraits(type).isRoot();
    }

    /**
     * Whether a resource of this type in this location is exported: the type has a content rule, the location is exactly {@code platform/} or
     * {@code public/}, and the bucket binding of the configuration API allows the type in that bucket.
     *
     * @param bucketLocation the decrypted bucket location of the dependency
     */
    static boolean isSupportedDependency(ResourceTypes type, String bucketLocation) {
        if (resolveContent(type) == null) {
            return false;
        }
        if (ResourceDescriptor.PLATFORM_LOCATION.equals(bucketLocation)) {
            return isBound(type, ResourceDescriptor.PLATFORM_BUCKET);
        }
        return ResourceDescriptor.PUBLIC_LOCATION.equals(bucketLocation) && isBound(type, ResourceDescriptor.PUBLIC_BUCKET);
    }

    /**
     * The bucket binding of the configuration API ({@link EntityBucketBinding}), checked before a resource is read or probed. It is keyed by
     * the URL segment of the type: {@code schemas} for an application type schema, not its blob group {@code app_type_schemas}.
     */
    static boolean isBound(ResourceTypes type, String bucketName) {
        if (type == ResourceTypes.SKILL) {
            // EntityBucketBinding has no entry for a complex resource; for the export a Skill is bound to the public bucket only
            return ResourceDescriptor.PUBLIC_BUCKET.equals(bucketName);
        }
        return EntityBucketBinding.isAllowed(type.urlSegment(), bucketName);
    }

    /**
     * @return what the export does with the body of the type; {@code null} for an unsupported type
     */
    @Nullable
    static Content resolveContent(ResourceTypes type) {
        return resolveTraits(type).content();
    }

    static List<ResourceTypes> listExaminedTypes() {
        return Arrays.stream(ResourceTypes.values()).filter(type -> resolveContent(type) == Content.EXAMINED).toList();
    }

    /**
     * @return the entity of that name, or {@code null} if there is none or the type has no entity
     */
    @Nullable
    static Deployment findDeployment(Config config, ResourceTypes type, String name) {
        EntityTraits entity = resolveTraits(type).entity();
        return entity == null ? null : entity.entities().apply(config).get(name);
    }

    /**
     * @throws IllegalArgumentException if the type has no entity that the export binds
     */
    static Class<? extends Deployment> resolveEntityClass(ResourceTypes type) {
        EntityTraits entity = resolveTraits(type).entity();
        if (entity == null) {
            throw new IllegalArgumentException("This type has no entity that the export binds");
        }
        return entity.entityClass();
    }

    /**
     * @return what removes the secrets from the projected tree of an entity of the type; {@code null} if the type has no entity
     */
    @Nullable
    static UnaryOperator<ObjectNode> resolveRedaction(ResourceTypes type) {
        EntityTraits entity = resolveTraits(type).entity();
        return entity == null ? null : entity.redaction();
    }
}
