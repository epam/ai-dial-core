package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Replays the corpus against both storage layouts and fails on any difference a caller could observe.
 *
 * <p>Runs under its own Gradle task ({@code ./gradlew :server:layoutDiffTest}) rather than in {@code :server:test}.
 * The active layout is process-wide static state and this suite exists to flip it, which is exactly the kind of
 * thing that makes unrelated classes fail elsewhere in the JVM.
 */
@Tag("layout-diff")
public class LayoutReplayDiffTest {

    private static final String TENANT = "layout-diff-tenant";

    private static final int LEGACY_REDIS_PORT = 16371;
    private static final int TENANT_ROOTED_REDIS_PORT = 16372;

    private static final Path REPORT_DIR = Paths.get("build", "reports", "layout-diff");

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testLayoutsAreIndistinguishableToCallers() {
        List<Scenario> corpus = CorpusRunner.loadCorpus(CorpusRunner.REPLAY_CORPUS);

        CorpusRunner.Run legacy = run("legacy", new JsonObject().put("tenantRooted", false),
                LEGACY_REDIS_PORT, corpus);
        CorpusRunner.Run tenantRooted = run("tenant-rooted", new JsonObject()
                        .put("tenantRooted", true)
                        .put("defaultTenant", TENANT),
                TENANT_ROOTED_REDIS_PORT, corpus);

        write("responses-legacy.json", legacy.responses());
        write("responses-tenant-rooted.json", tenantRooted.responses());

        assertEquals(legacy.buckets(), tenantRooted.buckets(),
                "Buckets differ between layouts; every url-bearing comparison below would be meaningless");

        List<Divergence> divergences = ResponseDiffer.diff(legacy.responses(), tenantRooted.responses());
        write("divergences.txt", divergences.stream().map(Divergence::describe).collect(Collectors.joining("\n\n")));

        ExpectedDivergences.Verdict verdict = ExpectedDivergences.load().classify(divergences);
        if (!verdict.clean()) {
            fail(report(verdict));
        }
    }

    private static CorpusRunner.Run run(String name, JsonObject layoutSettings, int redisPort, List<Scenario> corpus) {
        try (DialInstance instance = new DialInstance(name, layoutSettings, redisPort)) {
            return CorpusRunner.replay(instance, corpus);
        } finally {
            StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
        }
    }

    private static String report(ExpectedDivergences.Verdict verdict) {
        StringBuilder message = new StringBuilder();

        if (!verdict.unexplained().isEmpty()) {
            message.append(verdict.unexplained().size())
                    .append(" divergence(s) between the layouts are not accounted for.\n")
                    .append("Fix them, or record each one in server/src/test/resources/")
                    .append("layout-diff/expected-divergences.json with a reason and an issue.\n\n")
                    .append(verdict.unexplained().stream().map(Divergence::describe)
                            .collect(Collectors.joining("\n\n")));
        }

        if (!verdict.stale().isEmpty()) {
            message.append(message.isEmpty() ? "" : "\n\n")
                    .append(verdict.stale().size())
                    .append(" accepted divergence(s) no longer happen and should be removed:\n")
                    .append(verdict.stale().stream().map(ExpectedDivergences.Entry::toString)
                            .collect(Collectors.joining("\n")));
        }

        return message.toString();
    }

    @SneakyThrows
    private static void write(String file, Object content) {
        Files.createDirectories(REPORT_DIR);
        String text = content instanceof String plain
                ? plain
                : ProxyUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content);
        Files.writeString(REPORT_DIR.resolve(file), text);
    }
}
