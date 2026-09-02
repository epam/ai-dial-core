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

        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("current-user/skills/"))));
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

        assertTrue(validatorShapeMessage(validator, application).contains("current-user placeholder"));
    }

    @Test
    void rejectsUnknownRootSegment() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency("buckets/public/folder/"));

        assertTrue(validatorShapeMessage(validator, application).contains("global-view path"));
    }

    @Test
    void rejectsPlaceholderOutsideTheRoot() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);
        Application application = appWith(dependency("files/public/current-user/folder/"));

        assertTrue(validatorShapeMessage(validator, application).contains("only as the root segment"));
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
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%63urrent-user/f/"))).contains("only as the root segment"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("%75sers/bob/files/"))).contains("current-user placeholder"));
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

        assertTrue(validatorShapeMessage(validator, application).contains("exceeds"));
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

        assertDoesNotThrow(() -> validator.validateUserAuthored(appWith(dependency("current-user/skills/"))));
    }

    @Test
    void rejectsRootLevelCurrentUserWhileFlagOn() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(true);

        HttpException root = assertThrows(HttpException.class,
                () -> validator.validateUserAuthored(appWith(dependency("current-user/"))));
        HttpException untyped = assertThrows(HttpException.class,
                () -> validator.validateUserAuthored(appWith(dependency("current-user/rootstuff/"))));

        assertEquals(403, root.getStatus().getCode());
        assertEquals(403, untyped.getStatus().getCode());
        assertTrue(untyped.getMessage().contains("resource-type folder"));
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
