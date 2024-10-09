package com.epam.aidial.core.util;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        StringBuilder nameBuilder = new StringBuilder();
        for (int i = 0; i < input.length(); ) {
            Optional<RegexGroup> groupOpt = find(regexGroups, i);
            if (groupOpt.isEmpty()) {
                nameBuilder.append(input.charAt(i));
                i++;
            } else {
                RegexGroup group = groupOpt.get();
                nameBuilder.append("{").append(group.group()).append("}");
                i = group.end();
            }
        }
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
                    // Ignore
                }
            }
        }
        return regexGroups;
    }

    private Optional<RegexGroup> find(List<RegexGroup> groups, int position) {
        return groups.stream()
                .filter(g -> g.start() <= position && position < g.end())
                .findFirst();
    }

    private record RegexGroup(String group, int start, int end) {
    }
}
