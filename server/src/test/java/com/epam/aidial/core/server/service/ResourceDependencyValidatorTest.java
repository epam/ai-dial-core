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
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        // Token at i == 2 — a folder literally named {current-user} deeper in the path.
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/{current-user}/folder/")))
                .contains("only as the bucket segment"));

        // Token at i == 0 — placeholder used where the type belongs.
        assertTrue(validatorShapeMessage(validator, appWith(dependency("{current-user}/skills/")))
                .contains("declarable resource type"));

        // a bare "current-user" is an ordinary folder name, only the braced token is reserved
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/current-user/folder/"))));
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/public/current-user/x/"))));
    }

    @Test
    void rejectsMalformedBucketSegment() {
        // the bucket is opaque but must still be a valid path element: a target no request could ever resolve is a 400
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        for (String path : List.of("files/%0A/x/", "files/a%2Fb/x/", "files/{curent-user}/notes/", "files/%7Bfoo%7D/x/", "files/a%zz/x/")) {
            assertTrue(validatorShapeMessage(validator, appWith(dependency(path))).contains("not a valid resource url"), path);
        }
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/*/x/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/../x/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/%2e%2e/x/"))).contains("not a valid resource url"));
    }

    @Test
    void rejectsPathThatIsNotValidUrlEncoding() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        for (String path : List.of("files/public/my folder/", "files/public/100%/", "files/public/a%0Ab/")) {
            assertTrue(validatorShapeMessage(validator, appWith(dependency(path))).contains("not a valid resource url"), path);
        }
    }

    @Test
    void rejectsWildcardsAndRelativeSegments() {
        // dot segments are rejected by ResourceDescriptorFactory (#2165)
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/*/folder/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/../users/x/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public//folder/"))).contains("empty segments"));
    }

    @Test
    void rejectsPercentEncodedBannedTokens() {
        // the bans apply to decoded elements, so encoding a banned token does not smuggle it through
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%2e%2e/x/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%2a/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%7Bcurrent-user%7D/f/"))).contains("only as the bucket segment"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("%75sers/bob/files/"))).contains("{current-user}"));
    }

    @Test
    void rejectsEncodedSlashInBothForms() {
        // an encoded slash decodes inside one element: the factory rejects it, so "..%2F.." cannot become relative segments
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/{current-user}/..%2F..%2Fx/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/..%2Fx/"))).contains("not a valid resource url"));
    }

    @Test
    void rejectsBucketLevelTargetWithoutTrailingSlash() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public"))).contains("not the type root"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("skills/{current-user}"))).contains("not the type root"));
    }

    @Test
    void rejectsRepeatedTrailingSlash() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("skills/{current-user}//"))).contains("empty segments"));
    }

    @Test
    void rejectsCharactersNoStoredNameCanHave() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files/public/%7Bfoo%7D/"))).contains("not a valid resource url"));
    }

    @Test
    void acceptsPercentEncodedPlaceholder() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("skills/%7Bcurrent-user%7D/"))));
    }

    @Test
    void acceptsLegitimatelyEncodedSegments() {
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/public/my%20folder/"))));
    }

    @Test
    void rejectsSectionOverTheCap() {
        List<ResourceDependency> section = IntStream.rangeClosed(1, ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES + 1)
                .mapToObj(i -> dependency("files/public/folder" + i + "/").setLinkId("lnk_" + i).setAccess(Set.of()))
                .toList();
        Application application = new Application().setResourceDependencies(section);

        // the entries are invalid too, but over the cap none of them is inspected
        assertEquals(List.of("resourceDependencies: the section exceeds "
                + ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES + " entries"),
                ResourceDependencyValidator.shapeIssues(application));
    }

    @Test
    void rejectsBareTypeRootAsTooBroad() {
        // one segment is a whole global view across every bucket
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("files"))).contains("not the type root"));

        // "public" is a bucket, never a type
        assertTrue(validatorShapeMessage(validator, appWith(dependency("public/"))).contains("declarable resource type"));

        // type + bucket: one bucket's whole folder of that type
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("skills/{current-user}/"))));
        assertDoesNotThrow(() -> validator.validateShape(appWith(dependency("files/public/"))));
    }

    @Test
    void internalEngineTypesAreRejectedAtWriteTime() {
        // internal engine types are never declarable, in either form
        ResourceDependencyValidator validator = new ResourceDependencyValidator(false);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("credentials/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("keys/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("models/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("credentials/public/x/")))
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
        // the old root-level personal shapes are now shape errors (400), not a ceiling violation
        ResourceDependencyValidator validator = new ResourceDependencyValidator(true);

        assertTrue(validatorShapeMessage(validator, appWith(dependency("{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(validator, appWith(dependency("current-user/rootstuff/")))
                .contains("declarable resource type"));
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
