package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the checked-in corpus and replays it against one instance, recording what came back.
 *
 * <p>Scenarios share a single instance rather than getting one boot each: a boot costs seconds and the corpus is
 * expected to grow. Isolation is by convention instead — every scenario addresses paths under its own name.
 */
public class CorpusRunner {

    public static final String REPLAY_CORPUS = "layout-diff/corpus";

    private static final String API_KEY_1 = "proxyKey1";
    private static final String API_KEY_2 = "proxyKey2";

    /**
     * Identifies a response across both runs. Scenario and step names are the corpus's own, so a divergence
     * points at a line in a checked-in file rather than at an index.
     */
    public record StepKey(String scenario, String step) {
        @Override
        public String toString() {
            return scenario + " / " + step;
        }
    }

    @SneakyThrows
    public static List<Scenario> loadCorpus(String dir) {
        URI uri = Objects.requireNonNull(CorpusRunner.class.getClassLoader().getResource(dir),
                dir + " is missing from the test resources").toURI();

        List<Scenario> scenarios = new ArrayList<>();
        try (var files = Files.list(Paths.get(uri))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                try (InputStream in = Files.newInputStream(file)) {
                    scenarios.add(ProxyUtil.MAPPER.readValue(in, Scenario.class));
                }
            }
        }

        if (scenarios.isEmpty()) {
            throw new IllegalStateException("The corpus is empty; a green comparison would mean nothing");
        }
        return scenarios;
    }

    /**
     * One instance's side of the comparison. {@code buckets} is carried alongside the responses because the two
     * runs must agree on it before any response comparison means anything — buckets thread through nearly every
     * url in the corpus, so a difference there would make every other difference unreadable. {@code captures}
     * holds what each scenario captured, by scenario name: normalisation replaces a captured value with its
     * role name wherever it appears, so the values themselves are only comparable here.
     */
    public record Run(Map<String, String> buckets,
                      Map<String, Map<String, String>> captures,
                      Map<StepKey, RecordedResponse> responses) {
    }

    /**
     * Replays every scenario in order and returns the response of every step, keyed so that the two runs line
     * up. A step that fails to produce a response at all is a failure of the harness, not a divergence, and is
     * allowed to propagate.
     */
    public static Run replay(DialInstance instance, List<Scenario> scenarios) {
        Map<String, String> buckets = Map.of(
                "bucket1", instance.bucket(API_KEY_1),
                "bucket2", instance.bucket(API_KEY_2));

        Map<StepKey, RecordedResponse> recorded = new LinkedHashMap<>();
        Map<String, Map<String, String>> captures = new LinkedHashMap<>();
        for (Scenario scenario : scenarios) {
            Map<String, String> variables = new HashMap<>(buckets);
            Map<StepKey, RecordedResponse> raw = new LinkedHashMap<>();

            for (Scenario.Step step : scenario.steps()) {
                RecordedResponse response = instance.send(
                        step.method(),
                        substitute(step.path(), variables),
                        substituteQuery(step.query(), variables),
                        substitute(bodyText(step.body()), variables),
                        substituteValues(step.headersOrEmpty(), variables),
                        step.multipart());

                StepKey key = new StepKey(scenario.name(), step.name());
                if (raw.put(key, response) != null) {
                    throw new IllegalStateException("Duplicate step name in the corpus: " + key);
                }

                capture(step, response, variables);
            }

            // Normalisation waits for the end of the scenario: a value is only known to be a generated
            // identifier once some step has captured it, and the step that produced it ran before that.
            raw.forEach((key, response) -> recorded.put(key, ResponseNormalizer.normalize(response, variables)));

            Map<String, String> captured = new LinkedHashMap<>(variables);
            captured.keySet().removeAll(buckets.keySet());
            captures.put(scenario.name(), captured);
        }
        return new Run(buckets, captures, recorded);
    }

    private static void capture(Scenario.Step step, RecordedResponse response, Map<String, String> variables) {
        step.captureOrEmpty().forEach((name, capture) -> {
            JsonNode node = readTree(response.body()).at(capture.at());
            if (node.isMissingNode() || node.isNull()) {
                throw new IllegalStateException("Step '" + step.name() + "' cannot capture '" + name
                        + "' at '" + capture.at() + "' from: " + response.body());
            }
            variables.put(name, extract(step, name, capture, node.asText()));
        });

        step.captureHeadersOrEmpty().forEach((name, header) -> {
            String value = response.headers().get(header.toLowerCase());
            if (value == null) {
                throw new IllegalStateException("Step '" + step.name() + "' cannot capture '" + name
                        + "' from the missing header '" + header + "'");
            }
            variables.put(name, value);
        });
    }

    private static String extract(Scenario.Step step, String name, Scenario.Capture capture, String value) {
        if (capture.extract() == null) {
            return value;
        }

        Matcher matcher = Pattern.compile(capture.extract()).matcher(value);
        if (!matcher.find()) {
            throw new IllegalStateException("Step '" + step.name() + "' cannot capture '" + name + "': '"
                    + capture.extract() + "' does not match '" + value + "'");
        }
        return matcher.group(1);
    }

    @SneakyThrows
    private static JsonNode readTree(String body) {
        return ProxyUtil.MAPPER.readTree(body == null ? "{}" : body);
    }

    @SneakyThrows
    private static String bodyText(Object body) {
        if (body == null) {
            return null;
        }
        return body instanceof String text ? text : ProxyUtil.MAPPER.writeValueAsString(body);
    }

    private static Map<String, String> substituteValues(Map<String, String> values, Map<String, String> variables) {
        Map<String, String> resolved = new LinkedHashMap<>();
        values.forEach((name, value) -> resolved.put(name, substitute(value, variables)));
        return resolved;
    }

    /**
     * Query values ride in a URI, so substituted values are percent-encoded first — a page token is a raw
     * storage marker and can carry any character the blob store's keys can.
     */
    private static String substituteQuery(String template, Map<String, String> variables) {
        if (template == null) {
            return null;
        }

        Map<String, String> encoded = new LinkedHashMap<>();
        variables.forEach((name, value) -> encoded.put(name, URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return substitute(template, encoded);
    }

    private static String substitute(String template, Map<String, String> variables) {
        if (template == null) {
            return null;
        }

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
