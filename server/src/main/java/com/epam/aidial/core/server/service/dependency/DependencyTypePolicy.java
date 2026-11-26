package com.epam.aidial.core.server.service.dependency;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.security.EntityBucketBinding;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nullable;

/**
 * What the dependency list supports. A type that is not listed is unsupported, so a new resource type starts unsupported and produces
 * no element.
 */
final class DependencyTypePolicy {

    /** What the walk does with a resource of a type. */
    enum Content {
        /** A Deployment: its body is read and its references are followed. */
        EXAMINED,
        /** A schema, a File, a Skill or a Prompt: listed when it exists, its content is never examined. */
        LEAF
    }

    private record EntityTraits(Class<? extends Deployment> entityClass, Function<Config, Map<String, ? extends Deployment>> entities) {
    }

    /**
     * @param isRoot only an Application is accepted as a root for now
     * @param entity {@code null} unless the content is {@link Content#EXAMINED}
     */
    private record Traits(boolean isRoot, Content content, @Nullable EntityTraits entity) {
    }

    private static final Traits UNSUPPORTED = new Traits(false, null, null);

    private static final EntityTraits APPLICATION_ENTITY = new EntityTraits(Application.class, Config::getApplications);
    private static final EntityTraits MODEL_ENTITY = new EntityTraits(Model.class, Config::getModels);
    private static final EntityTraits INTERCEPTOR_ENTITY = new EntityTraits(Interceptor.class, Config::getInterceptors);

    private static final Map<ResourceTypes, Traits> TRAITS = Map.ofEntries(
            Map.entry(ResourceTypes.APPLICATION, new Traits(true, Content.EXAMINED, APPLICATION_ENTITY)),
            Map.entry(ResourceTypes.MODEL, new Traits(false, Content.EXAMINED, MODEL_ENTITY)),
            Map.entry(ResourceTypes.INTERCEPTOR, new Traits(false, Content.EXAMINED, INTERCEPTOR_ENTITY)),
            Map.entry(ResourceTypes.APP_TYPE_SCHEMA, new Traits(false, Content.LEAF, null)),
            Map.entry(ResourceTypes.CATALOG_SCHEMA, new Traits(false, Content.LEAF, null)),
            Map.entry(ResourceTypes.FILE, new Traits(false, Content.LEAF, null)),
            Map.entry(ResourceTypes.SKILL, new Traits(false, Content.LEAF, null)),
            Map.entry(ResourceTypes.PROMPT, new Traits(false, Content.LEAF, null)));

    private DependencyTypePolicy() {
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
     * Whether the walk may follow a resource: it is not a folder, its type has a content rule, and it is an entity of the file configuration or
     * lies in {@code platform/} or {@code public/}, in a bucket that the configuration API allows for its type. A caller's own stores are not
     * covered: the policy of a caller who is not an admin adds them.
     */
    static boolean isSupported(ResourceDescriptor target) {
        if (target.isFolder()) {
            return false;
        }
        ResourceTypes type = resolveType(target);
        if (FileConfigEntities.isFile(target)) {
            return resolveContent(type) != null;
        }
        return isSupportedDependency(type, target.getBucketLocation());
    }

    /**
     * Whether a resource of this type in this location is supported: the type has a content rule, the location is exactly {@code platform/} or
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
            // EntityBucketBinding has no entry for a complex resource; a Skill is bound to the public bucket only
            return ResourceDescriptor.PUBLIC_BUCKET.equals(bucketName);
        }
        return EntityBucketBinding.isAllowed(type.urlSegment(), bucketName);
    }

    /**
     * @return what the walk does with the body of the type; {@code null} for an unsupported type
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
     * @throws IllegalArgumentException if the type has no entity that the walk binds
     */
    static Class<? extends Deployment> resolveEntityClass(ResourceTypes type) {
        EntityTraits entity = resolveTraits(type).entity();
        if (entity == null) {
            throw new IllegalArgumentException("This type has no entity that the walk binds");
        }
        return entity.entityClass();
    }
}
