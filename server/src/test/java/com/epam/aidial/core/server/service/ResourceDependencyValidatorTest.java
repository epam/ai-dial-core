package com.epam.aidial.core.server.service;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.ResourceDependency;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every branch of the write-time rules: the shape table (400s) and the user-authored
 * governance ceiling (403s). These are pointer rules — no target permission is involved.
 */
public class ResourceDependencyValidatorTest {

    private static ResourceDependency dependency(String path) {
        return new ResourceDependency()
                .setKind(ResourceDependency.KIND)
                .setLinkId("lnk_1")
                .setTarget(new ResourceDependency.Target().setPath(path))
                .setAccess(Set.of(ResourceAccessType.READ));
    }

    private static Application appWith(ResourceDependency... dependencies) {
        return new Application().setResourceDependencies(List.of(dependencies));
    }

    // ---- shape: the two valid forms ----

    @Test
    void acceptsConcreteGlobalViewFolderTarget() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(
                appWith(dependency("files/public/policies/").setAccess(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)))));
    }

    @Test
    void acceptsCurrentUserPlaceholderTarget() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("skills/{current-user}/"))));
    }

    @Test
    void acceptsFileTargetWithoutTrailingSlash() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("prompts/public/my-prompt"))));
    }

    // ---- shape: record fields ----

    @Test
    void rejectsWrongKind() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency("files/public/folder/").setKind("dial.resource"));

        HttpException error = assertThrows(HttpException.class, () -> validator.validateShape(application));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("kind"));
    }

    @Test
    void rejectsMissingAndDuplicateLinkIds() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application missing = appWith(dependency("files/public/folder/").setLinkId(" "));
        Application duplicate = appWith(dependency("files/public/folder/"), dependency("files/public/other/"));

        assertTrue(validatorShapeMessage(validator, missing).contains("linkId is required"));
        assertTrue(validatorShapeMessage(validator, duplicate).contains("duplicate linkId"));
    }

    @Test
    void rejectsMissingTargetPath() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency(null));

        assertTrue(validatorShapeMessage(validator, application).contains("target.path is required"));
    }

    @Test
    void rejectsEmptyAccessAndShareAccess() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application empty = appWith(dependency("files/public/folder/").setAccess(Set.of()));
        Application share = appWith(dependency("files/public/folder/").setAccess(Set.of(ResourceAccessType.SHARE)));

        assertTrue(validatorShapeMessage(validator, empty).contains("access must not be empty"));
        assertTrue(validatorShapeMessage(validator, share).contains("SHARE"));
    }

    @Test
    void rejectsExplicitNullAccessAsValidationError() {
        // An explicit JSON null defeats the field default; it must surface as a 400, not a 500.
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency("files/public/folder/").setAccess(null));

        assertTrue(validatorShapeMessage(validator, application).contains("access must not be empty"));
    }

    @Test
    void rejectsNullEntry() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = new Application()
                .setResourceDependencies(Arrays.asList(dependency("files/public/folder/"), null));

        assertTrue(validatorShapeMessage(validator, application).contains("entry is null"));
    }

    // ---- shape: the target language ----

    @Test
    void rejectsConcreteUsersPathAsShapeError() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency("users/someone/files/folder/"));

        assertTrue(validatorShapeMessage(validator, application).contains("{current-user}"));
    }

    @Test
    void rejectsUnknownRootSegment() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency("buckets/public/folder/"));

        assertTrue(validatorShapeMessage(validator, application).contains("declarable resource type"));
    }

    @Test
    void rejectsPlaceholderOutsideTheBucketSlot() {
        // Under {type}/{bucket}/… the placeholder is legal only at segment 1 (the bucket slot).
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        // Token at i == 2 — a folder literally named {current-user} deeper in the path.
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/{current-user}/folder/")))
                .contains("only as the bucket segment"));

        // Token at i == 0 — placeholder used where the type belongs.
        assertTrue(validatorShapeMessage(validator, appWith(dependency("{current-user}/skills/")))
                .contains("only as the bucket segment"));

        // A bare word (no braces) mid-path is an ordinary, legal folder name — the ban is
        // positional-and-lexical (only the braced token, only at segment 1), not a lexical ban on
        // the word "current-user" anywhere in a path.
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/current-user/folder/"))));
    }

    @Test
    void rejectsWildcardsAndRelativeSegments() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/*/folder/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/../users/x/"))).contains("relative path segments"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public//folder/"))).contains("empty segments"));
    }

    @Test
    void rejectsPercentEncodedBannedTokens() {
        // The platform percent-decodes declared paths when building descriptors (the same single
        // tryDecodePath pass), so the token bans must run on decoded segments — raw-string checks
        // would let these smuggles through.
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%2e%2e/x/"))).contains("relative path segments"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%2a/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%7Bcurrent-user%7D/f/"))).contains("only as the bucket segment"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("%75sers/bob/files/"))).contains("{current-user}"));
    }

    @Test
    void acceptsLegitimatelyEncodedSegments() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/public/my%20folder/"))));
    }

    @Test
    void rejectsSectionOverTheCap() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        List<ResourceDependency> section = IntStream.rangeClosed(1, ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES + 1)
                .mapToObj(i -> dependency("files/public/folder" + i + "/").setLinkId("lnk_" + i))
                .toList();
        Application application = new Application().setResourceDependencies(section);

        // Over the cap the section is rejected on the cap alone — only the first MAX entries are
        // inspected, so a huge body cannot turn validation into unbounded allocation.
        List<String> issues = ResourceDependencyValidator.shapeIssues(application);
        assertTrue(issues.contains("resourceDependencies: the section exceeds "
                + ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES + " entries"));
        assertTrue(issues.size() <= ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES * 5);
    }

    @Test
    void rejectsBareTypeRootAsTooBroad() {
        // "files" alone addresses the whole global view of that type — as over-broad as the personal
        // root the governance ceiling bans. Declarations must be folder- or file-scoped.
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files"))).contains("not the type root"));

        // "public" is a bucket value, never a type (D-23a) — it is rejected now by the root-vocabulary
        // check, not by the removed dead "public" entry in the old GLOBAL_VIEW_ROOTS.
        assertTrue(validatorShapeMessage(validator, appWith(dependency("public/"))).contains("declarable resource type"));

        // Two segments is type + bucket — the whole of one bucket's folder of that type, the shape the
        // feature exists to serve (D-23c). The bar rejects one-segment paths only, for both forms.
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("skills/{current-user}/"))));
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/public/"))));
    }

    @Test
    void credentialsPlaceholderIsRejectedAtWriteTime() {
        // Regression guard for the deleted early return: without the unconditional root-vocabulary
        // check, credentials/{current-user}/ would have silently bypassed write-time validation and
        // been accepted, naming the user's secret-bearing blobs.
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("credentials/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("keys/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("models/{current-user}/")))
                .contains("declarable resource type"));
    }

    @Test
    void rejectsSlashOnlyTargetPath() {
        // A path of slashes only splits to zero segments — a shape error, not a crash.
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        ResourceDependencyValidator flagOnValidator = new ResourceDependencyValidator(true);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("//"))).contains("target.path is required"));
        assertDoesNotThrow(() -> flagOnValidator.validateUserAuthored(appWith(dependency("//"))));
    }

    @Test
    void emptyAndAbsentSectionsPassShape() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(new Application()));
        assertDoesNotThrow(() -> validator.validateShape(new Application().setResourceDependencies(List.of())));
    }

    // ---- the governance ceiling (user-authored) ----

    @Test
    void rejectsUserAuthoredSectionWhileFlagOff() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        HttpException error = assertThrows(HttpException.class,
                () -> validator.validateUserAuthored(appWith(dependency("files/public/folder/"))));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertTrue(error.getMessage().contains("may not declare"));
    }

    @Test
    void acceptsUserAuthoredSectionWhileFlagOn() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(true);

        assertDoesNotThrow(() -> validator.validateUserAuthored(appWith(dependency("skills/{current-user}/"))));
    }

    @Test
    void rootLevelPersonalDeclarationIsUnexpressible() {
        // D-23b: the per-path ceiling is gone — the grammar itself makes "write everything personal"
        // unexpressible, so both old root-level shapes now fail as shape errors (400, via
        // validateShape), not as a ceiling violation (403, via validateUserAuthored).
        ResourceDependencyValidator validator = new ResourceDependencyValidator(true);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("current-user/rootstuff/")))
                .contains("declarable resource type"));

        // A well-shaped personal target passes validateUserAuthored with the flag on...
        assertDoesNotThrow(() -> validator.validateUserAuthored(appWith(dependency("skills/{current-user}/"))));
        // ...and the ceiling itself now throws for no well-shaped path — it degenerates to the flag check.
        assertDoesNotThrow(() -> validator.validateUserAuthored(appWith(dependency("files/{current-user}/notes/"))));
    }

    @Test
    void ceilingIgnoresAppsWithoutSection() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateUserAuthored(new Application()));
        assertDoesNotThrow(() -> validator.validateUserAuthored(new Application().setResourceDependencies(List.of())));
    }

    // ---- the non-throwing form used by the lazy validator ----

    @Test
    void shapeIssuesListsAllProblemsWithoutThrowing() {
        Application application = appWith(
                dependency("files/public/*/").setKind("wrong").setAccess(Set.of()),
                dependency("users/x/y/"));

        List<String> issues = ResourceDependencyValidator.shapeIssues(application);

        assertTrue(issues.size() >= 5); // kind, access, wildcard, personal-target, and the second entry's issue
    }

    private static String validatorShapeMessage(ResourceDependencyValidator validator, Application application) {
        HttpException error = assertThrows(HttpException.class, () -> validator.validateShape(application));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        return error.getMessage();
    }
}
