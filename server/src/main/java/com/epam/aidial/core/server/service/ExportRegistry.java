package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.ExportResourceIdentity;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * The dependency registry of an Application export. It describes what the package holds and lacks and never holds a reference value,
 * credential, IRI, schema body, validation message or exception message. It does hold identities: the path of every resource of the package, in its fields and in its key, and the
 * identity of the target of an unresolved reference when one was identified.
 *
 * <p>The resources are a registry: every resource of the package appears once, under its key
 * ({@link ExportResourceIdentity#key()}), and every reference between resources is a {@link Dependency} of the resource that holds it. The
 * graph is flat: a dependency names its target by key and never embeds it, so cycles and shared dependencies need no special form.
 *
 * @param kind      distinguishes it from any other metadata file of an archive
 * @param roots     the keys of the selected Applications, in the order of the request
 * @param resources the resources by key, the selected ones first in the order of the request, then the dependencies in the order
 *                  they were found; a reader does not depend on the order of a JSON object, and takes the order of the roots from
 *                  {@code roots}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExportRegistry(String kind, int formatVersion,
                             List<String> roots, Map<String, Resource> resources, List<FileRecord> files,
                             Discovery discovery, Summary summary, List<String> warnings) {

    /**
     * A resource of the package, with the fields of its {@link ExportResourceIdentity}.
     *
     * @param role            {@code selected} or {@code dependency}
     * @param entryName       the archive entry of the resource: its storage path, or the path of its fragment
     * @param sourcePointer   the RFC 6901 pointer to its entity in the file configuration, for a resource of origin {@code file} only
     * @param dependencies    the references that the body of this resource holds, one entry per reference, in the order they were
     *                        found; empty for a resource whose body is not examined
     * @param requiredByRoots the keys of the selected Applications from which this resource is reached through included
     *                        dependencies, directly or transitively, in the order of the request; a root is never listed for
     *                        itself, and a root that another root reaches is listed for it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resource(String type, String origin, @Nullable String bucketLocation, String path, @Nullable String schemaId,
                           String role, String entryName, @Nullable String sourcePointer, List<Dependency> dependencies,
                           List<String> requiredByRoots) {

        public static Resource of(ExportResourceIdentity identity, String role, String entryName,
                                  List<Dependency> dependencies, List<String> requiredByRoots) {
            return new Resource(identity.type(), identity.origin(), identity.bucketLocation(), identity.path(), identity.schemaId(),
                    role, entryName, identity.sourcePointer(), dependencies, requiredByRoots);
        }

        /**
         * @return the identity that the fields of this resource form
         */
        public ExportResourceIdentity identity() {
            return new ExportResourceIdentity(type, origin, bucketLocation, path, schemaId);
        }
    }

    /**
     * One reference of a resource to another. It never holds the value of the reference.
     *
     * @param key       the key of the target in {@code resources}; set only when {@code status} is {@code included}, and then the
     *                  target is in the package. An unresolved reference names no resource that is not in the package
     * @param kind      the type of the target, when it is known
     * @param status    {@code included}, or the status of a reference that does not end in an included resource
     * @param pointer   RFC 6901 pointer into the body of the resource that holds the reference
     * @param valueKind {@code external-url}, {@code short-name} or {@code malformed}, when no target was identified
     * @param target    the identity of the target of an unresolved reference, when there is one
     * @param skipped   entities of that name that were skipped, for an unresolved reference; their reason is not listed, because
     *                  it is free text that can hold an exception message
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Dependency(@Nullable String key, @Nullable String kind, String status, String pointer, @Nullable String valueKind,
                             @Nullable ExportResourceIdentity target, @Nullable List<Skipped> skipped) {
    }

    public record Skipped(String type, String source) {
    }

    /**
     * A file whose bytes are in the archive as stored: the key of its resource, the key of the resource that owns it (a Skill, when Skills are
     * exported; absent for a File, which owns itself), its path and the name of its entry.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileRecord(String key, @Nullable String owner, String path, String entryName) {
    }

    public record Discovery(boolean complete, List<Issue> issues) {
    }

    /**
     * @param resource the key of the resource that holds the issue; every issue means that references may have been missed
     */
    public record Issue(String resource, String kind, String pointer) {
    }

    /**
     * @param edges the number of references per status that does not end in an included resource, with every status present
     */
    public record Summary(int resources, int files, int warnings, Map<String, Integer> edges) {
    }
}
