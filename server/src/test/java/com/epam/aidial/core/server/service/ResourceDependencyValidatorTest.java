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
 * governance ceiling (403s).
 */
public class ResourceDependencyValidatorTest {

    private static ResourceDependency dependency(String url) {
        return new ResourceDependency()
                .setKind(ResourceDependency.KIND)
                .setLinkId("lnk_1")
                .setTarget(new ResourceDependency.Target().setUrl(url))
                .setAccess(Set.of(ResourceAccessType.READ));
    }

    private static Application appWith(ResourceDependency... dependencies) {
        return new Application().setResourceDependencies(List.of(dependencies));
    }

    // ---- shape: the two valid forms ----

    @Test
    void acceptsConcreteGlobalViewFolderTarget() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(
                appWith(dependency("files/public/policies/").setAccess(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)))));
    }

    @Test
    void acceptsCurrentUserPlaceholderTarget() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("skills/{current-user}/"))));
    }

    @Test
    void acceptsProjectTargets() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("projects/{current-user}/")
                .setAccess(Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE)))));
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("projects/public/team-project/"))));
    }

    @Test
    void acceptsFileTargetWithoutTrailingSlash() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("prompts/public/my-prompt"))));
    }

    // ---- shape: record fields ----

    @Test
    void rejectsWrongKind() {
        Application application = appWith(dependency("files/public/folder/").setKind("dial.resource"));

        HttpException error = assertThrows(HttpException.class, () -> ResourceDependencyValidator.validateShape(application));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("kind"));
    }

    @Test
    void rejectsMissingAndDuplicateLinkIds() {
        Application missing = appWith(dependency("files/public/folder/").setLinkId(" "));
        Application duplicate = appWith(dependency("files/public/folder/"), dependency("files/public/other/"));

        assertTrue(validatorShapeMessage(missing).contains("linkId is required"));
        assertTrue(validatorShapeMessage(duplicate).contains("duplicate linkId"));
    }

    @Test
    void rejectsMissingTargetUrl() {
        Application application = appWith(dependency(null));

        assertTrue(validatorShapeMessage(application).contains("target.url is required"));
    }

    @Test
    void rejectsEmptyAccessAndShareAccess() {
        Application empty = appWith(dependency("files/public/folder/").setAccess(Set.of()));
        Application share = appWith(dependency("files/public/folder/").setAccess(Set.of(ResourceAccessType.SHARE)));

        assertTrue(validatorShapeMessage(empty).contains("access must not be empty"));
        assertTrue(validatorShapeMessage(share).contains("SHARE"));
    }

    @Test
    void rejectsWriteOnApplicationsAndToolsets() {
        Set<ResourceAccessType> readWrite = Set.of(ResourceAccessType.READ, ResourceAccessType.WRITE);

        assertTrue(validatorShapeMessage(appWith(dependency("applications/{current-user}/").setAccess(readWrite)))
                .contains("WRITE is not a dependency right on applications"));
        assertTrue(validatorShapeMessage(appWith(dependency("toolsets/public/x/").setAccess(Set.of(ResourceAccessType.WRITE))))
                .contains("WRITE is not a dependency right on toolsets"));
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("applications/{current-user}/"), dependency("toolsets/public/x/")
                .setLinkId("lnk_2"))));
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("files/{current-user}/").setAccess(readWrite))));
    }

    @Test
    void rejectsExplicitNullAccessAsValidationError() {
        // An explicit JSON null defeats the field default; it must surface as a 400, not a 500.
        Application application = appWith(dependency("files/public/folder/").setAccess(null));

        assertTrue(validatorShapeMessage(application).contains("access must not be empty"));
    }

    @Test
    void rejectsNullEntry() {
        Application application = new Application()
                .setResourceDependencies(Arrays.asList(dependency("files/public/folder/"), null));

        assertTrue(validatorShapeMessage(application).contains("entry is null"));
    }

    // ---- shape: the target language ----

    @Test
    void rejectsConcreteUsersUrlAsShapeError() {
        Application application = appWith(dependency("users/someone/files/folder/"));

        assertTrue(validatorShapeMessage(application).contains("{current-user}"));
    }

    @Test
    void rejectsUnknownRootSegment() {
        Application application = appWith(dependency("buckets/public/folder/"));

        assertTrue(validatorShapeMessage(application).contains("declarable resource type"));
    }

    @Test
    void rejectsPlaceholderOutsideTheBucketSlot() {
        // Token at i == 2 — a folder literally named {current-user} deeper in the url.
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/{current-user}/folder/")))
                .contains("only as the bucket segment"));

        // Token at i == 0 — placeholder used where the type belongs.
        assertTrue(validatorShapeMessage(appWith(dependency("{current-user}/skills/")))
                .contains("declarable resource type"));

        // a bare "current-user" is an ordinary folder name, only the braced token is reserved
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("files/current-user/folder/"))));
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("files/public/current-user/x/"))));
    }

    @Test
    void rejectsMalformedBucketSegment() {
        // the bucket is opaque at write time, but must still be a valid path element
        for (String url : List.of("files/%0A/x/", "files/a%2Fb/x/", "files/{curent-user}/notes/", "files/%7Bfoo%7D/x/", "files/a%zz/x/")) {
            assertTrue(validatorShapeMessage(appWith(dependency(url))).contains("not a valid resource url"), url);
        }
        assertTrue(validatorShapeMessage(appWith(dependency("files/*/x/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/../x/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/%2e%2e/x/"))).contains("not a valid resource url"));
    }

    @Test
    void rejectsTargetThatIsNotValidUrlEncoding() {
        for (String url : List.of("files/public/my folder/", "files/public/100%/", "files/public/a%0Ab/")) {
            assertTrue(validatorShapeMessage(appWith(dependency(url))).contains("not a valid resource url"), url);
        }
    }

    @Test
    void rejectsWildcardsAndRelativeSegments() {
        // dot segments are rejected by ResourceDescriptorFactory (#2165)
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/*/folder/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/../users/x/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/public//folder/"))).contains("empty segments"));
    }

    @Test
    void rejectsPercentEncodedBannedTokens() {
        // the bans apply to decoded elements, so encoding a banned token does not smuggle it through
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/%2e%2e/x/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/%2a/"))).contains("wildcards"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/%7Bcurrent-user%7D/f/"))).contains("only as the bucket segment"));
        assertTrue(validatorShapeMessage(appWith(dependency("%75sers/bob/files/"))).contains("{current-user}"));
    }

    @Test
    void rejectsEncodedSlashInBothForms() {
        // an encoded slash decodes inside one element: the factory rejects it, so "..%2F.." cannot become relative segments
        assertTrue(validatorShapeMessage(appWith(dependency("files/{current-user}/..%2F..%2Fx/"))).contains("not a valid resource url"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/..%2Fx/"))).contains("not a valid resource url"));
    }

    @Test
    void rejectsBucketLevelTargetWithoutTrailingSlash() {
        assertTrue(validatorShapeMessage(appWith(dependency("files/public"))).contains("not the type root"));
        assertTrue(validatorShapeMessage(appWith(dependency("skills/{current-user}"))).contains("not the type root"));
    }

    @Test
    void rejectsRepeatedTrailingSlash() {
        assertTrue(validatorShapeMessage(appWith(dependency("skills/{current-user}//"))).contains("empty segments"));
    }

    @Test
    void rejectsCharactersNoStoredNameCanHave() {
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/%7Bfoo%7D/"))).contains("not a valid resource url"));
    }

    @Test
    void acceptsPercentEncodedPlaceholder() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("skills/%7Bcurrent-user%7D/"))));
    }

    @Test
    void acceptsLegitimatelyEncodedSegments() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("files/public/my%20folder/"))));
    }

    @Test
    void rejectsSectionOverTheCap() {
        List<ResourceDependency> section = IntStream.rangeClosed(1, ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES + 1)
                .mapToObj(i -> dependency("files/public/folder" + i + "/").setLinkId("lnk_" + i).setAccess(Set.of()))
                .toList();
        Application application = new Application().setResourceDependencies(section);

        // the entries are invalid too, but over the cap none of them is inspected
        assertEquals("Invalid resource dependencies: resourceDependencies: the section exceeds "
                + ResourceDependencyValidator.MAX_DECLARED_DEPENDENCIES + " entries",
                ResourceDependencyValidator.findShapeError(application));
    }

    @Test
    void rejectsBareTypeRootAsTooBroad() {
        // one segment is a whole global view across every bucket
        assertTrue(validatorShapeMessage(appWith(dependency("files"))).contains("not the type root"));

        // "public" is a bucket, never a type
        assertTrue(validatorShapeMessage(appWith(dependency("public/"))).contains("declarable resource type"));

        // type + bucket: one bucket's whole folder of that type
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("skills/{current-user}/"))));
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(appWith(dependency("files/public/"))));
    }

    @Test
    void internalEngineTypesAreRejectedAtWriteTime() {
        // internal engine types are never declarable, in either form
        assertTrue(validatorShapeMessage(appWith(dependency("credentials/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(appWith(dependency("keys/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(appWith(dependency("models/{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(appWith(dependency("credentials/public/x/")))
                .contains("declarable resource type"));
    }

    @Test
    void rejectsSlashOnlyTargetUrl() {
        // A url of slashes only splits to zero segments — a shape error, not a crash.
        assertTrue(validatorShapeMessage(appWith(dependency("//"))).contains("target.url is required"));
    }

    @Test
    void emptyAndAbsentSectionsPassShape() {
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(new Application()));
        assertDoesNotThrow(() -> ResourceDependencyValidator.validateShape(new Application().setResourceDependencies(List.of())));
    }

    @Test
    void rootLevelPersonalDeclarationIsUnexpressible() {
        // the old root-level personal shapes are now shape errors (400), not a ceiling violation
        assertTrue(validatorShapeMessage(appWith(dependency("{current-user}/")))
                .contains("declarable resource type"));
        assertTrue(validatorShapeMessage(appWith(dependency("current-user/rootstuff/")))
                .contains("declarable resource type"));
    }

    // ---- findShapeError ----

    @Test
    void findShapeErrorReportsEveryProblem() {
        Application application = appWith(
                dependency("files/public/*/").setKind("wrong").setAccess(Set.of()),
                dependency("users/x/y/"));

        String error = ResourceDependencyValidator.findShapeError(application);

        assertTrue(error.contains("[0]: kind must be"), error);
        assertTrue(error.contains("[0]: wildcards are not allowed"), error);
        assertTrue(error.contains("[0]: access must not be empty"), error);
        assertTrue(error.contains("[1]: personal targets are declared as"), error);
    }

    @Test
    void findShapeErrorIsNullForValidSection() {
        assertEquals(null, ResourceDependencyValidator.findShapeError(appWith(dependency("skills/{current-user}/"))));
    }

    @Test
    void rejectsSurroundingWhitespaceInTargetUrl() {
        assertTrue(validatorShapeMessage(appWith(dependency(" files/public/f/"))).contains("surrounding whitespace"));
        assertTrue(validatorShapeMessage(appWith(dependency("files/public/f/ "))).contains("surrounding whitespace"));
        assertTrue(validatorShapeMessage(appWith(dependency("\tskills/{current-user}/"))).contains("surrounding whitespace"));
    }

    private static String validatorShapeMessage(Application application) {
        HttpException error = assertThrows(HttpException.class, () -> ResourceDependencyValidator.validateShape(application));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        return error.getMessage();
    }
}
