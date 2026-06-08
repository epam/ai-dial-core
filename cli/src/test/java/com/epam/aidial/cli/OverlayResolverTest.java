package com.epam.aidial.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayResolverTest {

    private List<ManifestLoader.Manifest> loadBase(Path baseRoot) throws Exception {
        return ManifestLoader.load(baseRoot);
    }

    private void writeBaseModel(Path baseRoot, String fileRelPath, String name, String endpoint) throws Exception {
        Path f = baseRoot.resolve(fileRelPath);
        Files.createDirectories(f.getParent());
        Files.writeString(f, "kind: Model\nname: models/public/" + name + "\nspec:\n"
                + "  type: chat\n  endpoint: " + endpoint + "\n");
    }

    @Test
    void overlayPatchesSpec(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m1.yaml", "m1", "http://base");

        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m1.yaml"), """
                kind: ModelOverlay
                target: models/public/m1
                patch:
                  endpoint: http://patched
                  pricing:
                    prompt: 0.25
                """);

        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertEquals(1, out.size());
        ManifestLoader.Manifest m = out.get(0);
        assertEquals("Model", m.kind());
        assertEquals("m1", m.name());
        assertEquals("http://patched", m.spec().path("endpoint").asText());
        assertEquals("chat", m.spec().path("type").asText(), "untouched base field preserved");
        assertEquals(0.25, m.spec().path("pricing").path("prompt").asDouble(), 0.0001);
    }

    @Test
    void overlayNullDeletesField(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://x");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                patch:
                  type: null
                """);
        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertTrue(out.get(0).spec().path("type").isMissingNode(), "null deletes per RFC 7396");
    }

    @Test
    void overlayParamsOverrideBaseParams(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(baseRoot.resolve("models"));
        Files.writeString(baseRoot.resolve("models/m.yaml"), """
                kind: Model
                name: models/public/m
                params:
                  region: us-east-1
                  rate: 100
                spec:
                  type: chat
                """);
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                params:
                  region: us-west-2
                """);
        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertEquals("us-west-2", out.get(0).params().get("region"));
        assertEquals(100, out.get(0).params().get("rate"), "unset overlay key preserves base param");
    }

    @Test
    void disableMarkerRemovesEntity(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/keep.yaml", "keep", "http://k");
        writeBaseModel(baseRoot, "models/drop.yaml", "drop", "http://d");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/drop.disable"), "");

        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertEquals(1, out.size());
        assertEquals("keep", out.get(0).name());
    }

    @Test
    void disableMarkerNonEmptyRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/m.disable"), "not empty");

        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("must be empty"), e.getMessage());
    }

    @Test
    void disableMarkerNoMatchingBaseRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/typo.disable"), "");

        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("matches no base"), e.getMessage());
    }

    @Test
    void disableStemBytewiseMatch(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/anthropic.claude-sonnet-4-6.yaml", "claude", "http://c");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/anthropic.claude-sonnet-4-6.disable"), "");

        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertTrue(out.isEmpty(), "byte-equal stem under same relative dir matches");
    }

    @Test
    void disableYamlSuffixMarkerStemMustNotIncludeExtension(@TempDir Path tmp) throws Exception {
        // Marker stem 'anthropic.claude-sonnet-4-6.yaml' does NOT match
        // base stem 'anthropic.claude-sonnet-4-6'. Strip-last-suffix only.
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/anthropic.claude-sonnet-4-6.yaml", "claude", "http://c");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/anthropic.claude-sonnet-4-6.yaml.disable"), "");

        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("matches no base"), e.getMessage());
    }

    @Test
    void disableDifferentRelativeDirRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot.resolve("applications"));
        Files.writeString(overlayRoot.resolve("applications/m.disable"), "");

        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("matches no base"), e.getMessage());
    }

    @Test
    void overlayKindAndTargetPrefixMustAgree(@TempDir Path tmp) throws Exception {
        // RoleOverlay must target a roles/platform/... canonical id, not models/public/...
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("oops.yaml"), """
                kind: RoleOverlay
                target: models/public/m
                patch:
                  endpoint: http://patched
                """);
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("roles/platform/"), e.getMessage());
    }

    @Test
    void overlayMissingTargetBaseRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("ghost.yaml"), """
                kind: ModelOverlay
                target: models/public/ghost
                patch:
                  endpoint: http://x
                """);
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("matches no base manifest"), e.getMessage());
    }

    @Test
    void duplicateTargetRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot.resolve("models"));
        Files.writeString(overlayRoot.resolve("models/a.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                patch: { endpoint: http://one }
                """);
        Files.writeString(overlayRoot.resolve("models/b.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                patch: { endpoint: http://two }
                """);
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void emptyOverlayDocRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("ovl.yaml"), """
                kind: ModelOverlay
                target: models/public/m
                """);
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("must declare 'patch' or 'params'"), e.getMessage());
    }

    @Test
    void hiddenPathsSkipped(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot.resolve(".git"));
        Files.writeString(overlayRoot.resolve(".git/junk.yaml"), "kind: not-a-real-thing\n");
        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertEquals(1, out.size());
    }

    @Test
    void emptyOverlayDirNoOp(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot);
        List<ManifestLoader.Manifest> out = OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot);
        assertEquals(1, out.size());
        assertEquals("http://m", out.get(0).spec().path("endpoint").asText());
    }

    @Test
    void disableWithSingleFileBaseRejected(@TempDir Path tmp) throws Exception {
        Path baseFile = tmp.resolve("m.yaml");
        Files.writeString(baseFile, "kind: Model\nname: models/public/m\nspec:\n  type: chat\n");
        Path overlayRoot = tmp.resolve("overlay");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("m.disable"), "");
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseFile), baseFile, overlayRoot));
        assertTrue(e.getMessage().contains(".disable") && e.getMessage().contains("-f"), e.getMessage());
    }

    @Test
    void settingsOverlayRejectedWithCleanError(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("s.yaml"), """
                kind: SettingsOverlay
                target: settings/platform/global
                patch:
                  flag: true
                """);
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("not supported"), e.getMessage());
    }

    @Test
    void overlayTargetNotCanonicalRejected(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        Path overlayRoot = tmp.resolve("overlay");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        Files.createDirectories(overlayRoot);
        Files.writeString(overlayRoot.resolve("bad.yaml"), """
                kind: ModelOverlay
                target: m
                patch:
                  endpoint: http://x
                """);
        OverlayResolver.OverlayResolveException e = assertThrows(OverlayResolver.OverlayResolveException.class,
                () -> OverlayResolver.apply(loadBase(baseRoot), baseRoot, overlayRoot));
        assertTrue(e.getMessage().contains("canonical"), e.getMessage());
    }

    @Test
    void manifestSourcePathPopulated(@TempDir Path tmp) throws Exception {
        Path baseRoot = tmp.resolve("base");
        writeBaseModel(baseRoot, "models/m.yaml", "m", "http://m");
        List<ManifestLoader.Manifest> loaded = ManifestLoader.load(baseRoot);
        assertEquals(baseRoot.resolve("models/m.yaml"), loaded.get(0).source());

        Path baseFile = tmp.resolve("solo.yaml");
        Files.writeString(baseFile, "kind: Model\nname: models/public/m\nspec:\n  type: chat\n");
        // single-file load: source still set to the file itself.
        assertEquals(baseFile, ManifestLoader.load(baseFile).get(0).source());

        // sanity null-check seam: a synthetic Manifest with null source compiles + reads back null.
        ManifestLoader.Manifest synthetic = new ManifestLoader.Manifest(
                "Model", "x", loaded.get(0).spec(), null, null, java.util.Map.of(), null);
        assertNull(synthetic.source());
    }
}
