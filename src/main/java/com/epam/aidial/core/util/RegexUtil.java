package com.epam.aidial.core.util;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class RegexUtil {

    public String replaceNamedGroups(Pattern pattern, String input, List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return input;
        }
        List<RegexGroup> regexGroups = collectGroups(pattern, input, groups);
        if (regexGroups.isEmpty()) {
            return input;
        }
        regexGroups.sort(Comparator.comparingInt(RegexGroup::start));
        StringBuilder nameBuilder = new StringBuilder();
        int prev = 0;
        for (RegexGroup rg : regexGroups) {
            nameBuilder
                    .append(input, prev, rg.start())
                    .append('{').append(rg.group()).append('}');
            prev = rg.end();
        }
        nameBuilder.append(input, prev, input.length());
        return nameBuilder.toString();
    }

    private List<RegexGroup> collectGroups(Pattern pattern, String input, List<String> groups) {
        List<RegexGroup> regexGroups = new ArrayList<>();
        Matcher matcher = pattern.matcher(input);
        if (matcher.matches() && matcher.groupCount() > 0) {
            for (String group : groups) {
                try {
                    int start = matcher.start(group);
                    int end = matcher.end(group);
                    regexGroups.add(new RegexGroup(group, start, end));
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    //Ignore group mismatch
                }
            }
        }
        return regexGroups;
    }

    private record RegexGroup(String group, int start, int end) {
    }
}
