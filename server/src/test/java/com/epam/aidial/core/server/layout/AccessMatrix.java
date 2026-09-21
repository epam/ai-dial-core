package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Asks "what may this subject do to this resource?" of a running instance, once per cell, and records the
 * answer so the two layouts can be compared on decisions rather than on responses.
 *
 * <p>The question goes over HTTP rather than into {@code AccessService} directly. {@code populatePermissions}
 * runs the same eleven-rule chain and puts its result in the response, so this observes the real chain with a
 * real context — where a hand-built context would mostly prove that the mock was set up the way the test
 * expected.
 */
public class AccessMatrix {

    private static final String MATRIX = "layout-diff/access-matrix.json";

    /**
     * @param subject  who is asking — a key in the corpus's subject table
     * @param resource logical url, with {@code ${…}} placeholders resolved from the seed run
     * @param rule     the permission rule this cell exists to exercise, for the coverage report
     * @param expects  the permissions this cell must yield. A cell that grants less than this is not
     *                 exercising its rule, and a matrix that quietly stopped exercising the chain would
     *                 compare "denied" to "denied" and call the layouts identical.
     * @param forbids  permissions this cell must not yield, for the boundaries worth pinning — a read-only
     *                 share that starts granting write is not something to notice only in P3.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Cell(String name, String subject, String resource, String rule,
                       List<String> expects, List<String> forbids) {

        public List<String> forbidsOrEmpty() {
            return forbids == null ? List.of() : forbids;
        }
    }

    /**
     * What a subject turns into on the wire. {@code perRequest} builds an application caller — a per-request
     * key issued against {@code apiKey}, optionally carrying attached deployments or its own source app.
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Subject(String name, String apiKey, String authorization, PerRequest perRequest) {

        @JsonIgnoreProperties(ignoreUnknown = false)
        public record PerRequest(String sourceDeployment, Map<String, List<String>> attach,
                                 Map<String, List<String>> share) {
        }
    }

    /**
     * @param chain     every rule in {@code AccessService}'s permission chain, so a rule that no cell reaches
     *                  is a visible gap rather than an absence nobody notices
     * @param uncovered rules deliberately not covered, and why
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Definition(List<String> chain, Map<String, String> uncovered,
                             List<Subject> subjects, List<Cell> cells) {
    }

    /**
     * One cell's answer: the permissions granted, or why none were.
     */
    public record Decision(String cell, String rule, int status, Set<String> permissions) {

        public String describe() {
            return status == 200 ? String.join(",", permissions) : "http " + status;
        }
    }

    @SneakyThrows
    public static Definition load() {
        try (InputStream in = AccessMatrix.class.getClassLoader().getResourceAsStream(MATRIX)) {
            if (in == null) {
                throw new IllegalStateException(MATRIX + " is missing");
            }
            return ProxyUtil.MAPPER.readValue(in, new TypeReference<Definition>() { });
        }
    }

    /**
     * Evaluates every cell against one instance. Placeholders resolve from {@code variables}, which the seed
     * run filled in — buckets, and anything the seed scenarios captured.
     */
    public static Map<String, Decision> evaluate(DialInstance instance, Definition definition,
                                                 Map<String, String> variables) {
        Map<String, Map<String, String>> credentials = resolveSubjects(instance, definition.subjects(), variables);

        Map<String, Decision> decisions = new LinkedHashMap<>();
        for (Cell cell : definition.cells()) {
            Map<String, String> headers = credentials.get(cell.subject());
            if (headers == null) {
                throw new IllegalStateException("Cell '" + cell.name() + "' names unknown subject " + cell.subject());
            }

            String url = resolve(cell.resource(), variables);
            RecordedResponse response =
                    instance.send("GET", "/v1/metadata/" + url, "permissions=true", null, headers, null);

            decisions.put(cell.name(), new Decision(cell.name(), cell.rule(), response.status(),
                    permissions(response)));
        }
        return decisions;
    }

    private static Set<String> permissions(RecordedResponse response) {
        if (response.status() != 200 || response.body() == null) {
            return Set.of();
        }

        Set<String> granted = new TreeSet<>();
        var array = new JsonObject(response.body()).getJsonArray("permissions");
        if (array != null) {
            array.forEach(permission -> granted.add(String.valueOf(permission)));
        }
        return granted;
    }

    /**
     * Turns each subject into the credential its requests carry. Per-request keys are minted here rather than
     * declared in the corpus because only a live instance can issue one.
     */
    private static Map<String, Map<String, String>> resolveSubjects(DialInstance instance, List<Subject> subjects,
                                                                  Map<String, String> variables) {
        Map<String, Map<String, String>> credentials = new LinkedHashMap<>();
        for (Subject subject : subjects) {
            if (subject.authorization() != null) {
                credentials.put(subject.name(), Map.of("authorization", subject.authorization()));
            } else if (subject.perRequest() == null) {
                credentials.put(subject.name(), Map.of("api-key", subject.apiKey()));
            } else {
                credentials.put(subject.name(), Map.of("api-key", instance.issuePerRequestKey(resolve(subject, variables))));
            }
        }
        return credentials;
    }

    private static Subject resolve(Subject subject, Map<String, String> variables) {
        Subject.PerRequest perRequest = subject.perRequest();
        return new Subject(subject.name(), subject.apiKey(), subject.authorization(),
                new Subject.PerRequest(resolve(perRequest.sourceDeployment(), variables),
                        resolve(perRequest.attach(), variables), resolve(perRequest.share(), variables)));
    }

    private static Map<String, List<String>> resolve(Map<String, List<String>> urls, Map<String, String> variables) {
        if (urls == null) {
            return null;
        }
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        urls.forEach((url, permissions) -> resolved.put(resolve(url, variables), permissions));
        return resolved;
    }

    private static String resolve(String template, Map<String, String> variables) {
        String resolved = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            resolved = resolved.replace("${" + variable.getKey() + "}", variable.getValue());
        }
        int unresolved = resolved.indexOf("${");
        if (unresolved >= 0) {
            throw new IllegalStateException("Unresolved placeholder in: " + resolved.substring(unresolved));
        }
        return resolved;
    }
}
