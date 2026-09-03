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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asks the same access questions of both layouts and fails on any answer that differs.
 *
 * <p>It should come back clean: shares and rules key on logical urls and {@code bucketLocation} stays legacy,
 * so the inputs to the permission chain never change. That is an assumption spanning eleven rules — cheap to
 * verify, expensive to be wrong about — and the same differ is what answers "does the new engine decide
 * identically?" in P3, when there is a genuine rewrite to check and no reference implementation left to
 * capture behaviour from.
 */
@Tag("layout-diff")
public class LayoutAccessDifferTest {

    private static final String TENANT = "layout-diff-tenant";

    private static final int LEGACY_REDIS_PORT = 16373;
    private static final int TENANT_ROOTED_REDIS_PORT = 16374;

    private static final Path REPORT_DIR = Paths.get("build", "reports", "layout-diff");

    private record Run(Map<String, String> variables, Map<String, AccessMatrix.Decision> decisions) {
    }

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testBothLayoutsDecideIdentically() {
        List<Scenario> seed = CorpusRunner.loadCorpus(CorpusRunner.ACCESS_CORPUS);
        AccessMatrix.Definition matrix = AccessMatrix.load();

        Run legacy = run("access-legacy", new JsonObject().put("tenantRooted", false),
                LEGACY_REDIS_PORT, seed, matrix);
        Run tenantRooted = run("access-tenant-rooted", new JsonObject()
                        .put("tenantRooted", true)
                        .put("defaultTenant", TENANT),
                TENANT_ROOTED_REDIS_PORT, seed, matrix);

        write("access-legacy.json", legacy.decisions());
        write("access-tenant-rooted.json", tenantRooted.decisions());

        List<String> problems = new ArrayList<>();
        problems.addAll(vacuousCells(matrix, legacy.decisions()));
        problems.addAll(uncoveredRules(matrix, legacy.decisions()));
        problems.addAll(divergences(legacy.decisions(), tenantRooted.decisions()));

        if (!problems.isEmpty()) {
            fail(String.join("\n", problems));
        }
    }

    /**
     * A cell that grants less than it declares is not exercising its rule. Without this the suite would
     * happily compare "denied" to "denied" across every cell and report the layouts identical. The dual
     * holds for denial cells: empty {@code expects} asserts that nothing is granted, not that nothing is
     * expected — otherwise both layouts granting the stranger READ would compare equal and pass, and
     * lookupPermissions is a union over the whole chain, so denial means no rule granted anywhere.
     */
    private static List<String> vacuousCells(AccessMatrix.Definition matrix,
                                             Map<String, AccessMatrix.Decision> decisions) {
        List<String> problems = new ArrayList<>();
        for (AccessMatrix.Cell cell : matrix.cells()) {
            AccessMatrix.Decision decision = decisions.get(cell.name());
            if (cell.expects().isEmpty() && !decision.permissions().isEmpty()) {
                problems.add("Cell '" + cell.name() + "' (" + cell.rule() + ") expects denial but got "
                        + decision.describe() + ".");
            }
            if (!decision.permissions().containsAll(cell.expects())) {
                problems.add("Cell '" + cell.name() + "' (" + cell.rule() + ") expected at least "
                        + cell.expects() + " but got " + decision.describe()
                        + " — the matrix is not exercising the rule it claims to.");
            }
            for (String forbidden : cell.forbidsOrEmpty()) {
                if (decision.permissions().contains(forbidden)) {
                    problems.add("Cell '" + cell.name() + "' (" + cell.rule() + ") must not grant "
                            + forbidden + " but got " + decision.describe() + ".");
                }
            }
        }
        return problems;
    }

    /**
     * The chain is a union over eleven rules, and the ones that fire rarely are the ones a re-addressing bug
     * would break silently. A rule with no cell that actually grants through it is not being compared at all.
     */
    private static List<String> uncoveredRules(AccessMatrix.Definition matrix,
                                               Map<String, AccessMatrix.Decision> decisions) {
        Set<String> granting = new LinkedHashSet<>();
        for (AccessMatrix.Cell cell : matrix.cells()) {
            if (!decisions.get(cell.name()).permissions().isEmpty()) {
                granting.add(cell.rule());
            }
        }

        List<String> problems = new ArrayList<>();
        for (String rule : matrix.chain()) {
            if (granting.contains(rule)) {
                continue;
            }
            String reason = matrix.uncovered().get(rule);
            if (reason == null) {
                problems.add("Rule '" + rule + "' in the permission chain is not covered by any cell that "
                        + "grants, and is not listed under \"uncovered\" with a reason.");
            }
        }
        return problems;
    }

    private static List<String> divergences(Map<String, AccessMatrix.Decision> legacy,
                                            Map<String, AccessMatrix.Decision> tenantRooted) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, AccessMatrix.Decision> entry : legacy.entrySet()) {
            AccessMatrix.Decision left = entry.getValue();
            AccessMatrix.Decision right = tenantRooted.get(entry.getKey());
            if (right == null || !left.permissions().equals(right.permissions()) || left.status() != right.status()) {
                problems.add("Access decision differs for '" + entry.getKey() + "' (" + left.rule() + "):\n"
                        + "  legacy:       " + left.describe() + "\n"
                        + "  tenantRooted: " + (right == null ? "missing" : right.describe()));
            }
        }
        return problems;
    }

    private static Run run(String name, JsonObject layoutSettings, int redisPort,
                           List<Scenario> seed, AccessMatrix.Definition matrix) {
        try (DialInstance instance = new DialInstance(name, layoutSettings, redisPort)) {
            CorpusRunner.Run seeded = CorpusRunner.replay(instance, seed);
            return new Run(seeded.variables(), AccessMatrix.evaluate(instance, matrix, seeded.variables()));
        } finally {
            StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
        }
    }

    @SneakyThrows
    private static void write(String file, Object content) {
        Files.createDirectories(REPORT_DIR);
        Files.writeString(REPORT_DIR.resolve(file),
                ProxyUtil.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(content));
    }
}
