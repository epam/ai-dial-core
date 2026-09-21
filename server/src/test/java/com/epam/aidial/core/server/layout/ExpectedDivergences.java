package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.SneakyThrows;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The checked-in list of accepted divergences — the mechanism that turns "every difference is deliberate"
 * into something reviewable. Anything not listed here fails the run.
 *
 * <p>A stale entry fails too. An expectations file that accumulates entries nobody can still justify is how a
 * comparison suite becomes decorative, so an entry that matches nothing is treated as a defect in the file.
 */
public record ExpectedDivergences(List<Entry> entries) {

    private static final String LOCATION = "layout-diff/expected-divergences.json";

    /**
     * @param reason why the difference is acceptable, in prose — this is what a reviewer reads
     * @param issue  where the difference is tracked; an accepted divergence with nothing tracking it is a
     *               divergence nobody has committed to resolving
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Entry(String scenario, String step, String field, String reason, String issue) {

        public boolean matches(Divergence divergence) {
            return Objects.equals(scenario, divergence.step().scenario())
                    && Objects.equals(step, divergence.step().step())
                    && Objects.equals(field, divergence.field());
        }

        @Override
        public String toString() {
            return scenario + " / " + step + " [" + field + "]";
        }
    }

    /**
     * What the run has to answer for: divergences no entry accounts for, and entries that matched nothing.
     */
    public record Verdict(List<Divergence> unexplained, List<Entry> stale) {
        public boolean clean() {
            return unexplained.isEmpty() && stale.isEmpty();
        }
    }

    @SneakyThrows
    public static ExpectedDivergences load() {
        try (InputStream in = ExpectedDivergences.class.getClassLoader().getResourceAsStream(LOCATION)) {
            if (in == null) {
                throw new IllegalStateException(LOCATION + " is missing; the run has no record of what is accepted");
            }
            return new ExpectedDivergences(ProxyUtil.MAPPER.readValue(in, new TypeReference<List<Entry>>() { }));
        }
    }

    public Verdict classify(List<Divergence> divergences) {
        List<Divergence> unexplained = new ArrayList<>();
        Set<Entry> matched = new LinkedHashSet<>();

        for (Divergence divergence : divergences) {
            Entry entry = entries.stream().filter(candidate -> candidate.matches(divergence)).findFirst().orElse(null);
            if (entry == null) {
                unexplained.add(divergence);
            } else {
                matched.add(entry);
            }
        }

        List<Entry> stale = entries.stream().filter(entry -> !matched.contains(entry)).toList();
        return new Verdict(unexplained, stale);
    }
}
