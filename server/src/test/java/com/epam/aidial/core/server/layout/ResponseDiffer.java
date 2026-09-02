package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.layout.CorpusRunner.StepKey;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

@UtilityClass
public class ResponseDiffer {

    public static List<Divergence> diff(Map<StepKey, RecordedResponse> legacy,
                                        Map<StepKey, RecordedResponse> tenantRooted) {
        if (!legacy.keySet().equals(tenantRooted.keySet())) {
            throw new IllegalStateException("The two runs replayed different steps; the corpus is not deterministic");
        }

        List<Divergence> divergences = new ArrayList<>();
        legacy.forEach((step, left) -> compare(step, left, tenantRooted.get(step), divergences));
        return divergences;
    }

    private static void compare(StepKey step, RecordedResponse legacy, RecordedResponse tenantRooted,
                                List<Divergence> divergences) {
        if (legacy.status() != tenantRooted.status()) {
            divergences.add(new Divergence(step, "status",
                    String.valueOf(legacy.status()), String.valueOf(tenantRooted.status())));
        }

        if (!Objects.equals(legacy.body(), tenantRooted.body())) {
            divergences.add(new Divergence(step, "body", legacy.body(), tenantRooted.body()));
        }

        for (String header : new TreeSet<>(union(legacy.headers(), tenantRooted.headers()))) {
            String left = legacy.headers().get(header);
            String right = tenantRooted.headers().get(header);
            if (!Objects.equals(left, right)) {
                divergences.add(new Divergence(step, "header:" + header, left, right));
            }
        }
    }

    private static List<String> union(Map<String, String> left, Map<String, String> right) {
        List<String> names = new ArrayList<>(left.keySet());
        right.keySet().stream().filter(name -> !left.containsKey(name)).forEach(names::add);
        return names;
    }
}
