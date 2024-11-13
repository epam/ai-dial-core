package com.epam.aidial.core.server.controller;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class RegexUtil {

    String replaceNamedGroups(Pattern pattern, String path) {
        if (pattern == null || path == null || path.isBlank()) {
            return path;
        }
        List<RegexGroup> regexGroups = collectGroups(pattern, path);
        if (regexGroups.isEmpty()) {
            return path;
        }
        regexGroups.sort(Comparator.comparingInt(RegexGroup::start));
        StringBuilder nameBuilder = new StringBuilder();
        int prev = 0;
        for (RegexGroup rg : regexGroups) {
            nameBuilder
                    .append(path, prev, rg.start())
                    .append('{').append(rg.group()).append('}');
            prev = rg.end();
        }
        nameBuilder.append(path, prev, path.length());
        return nameBuilder.toString();
    }

    private List<RegexGroup> collectGroups(Pattern pattern, String path) {
        List<RegexGroup> regexGroups = new ArrayList<>();
        Set<String> groups = getNamedGroups(pattern);
        if (groups.isEmpty()) {
            return regexGroups;
        }
        Matcher matcher = pattern.matcher(path);
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

    private static Set<String> getNamedGroups(Pattern pattern) {
        Set<String> namedGroups = new HashSet<>();

        Matcher matcher = Pattern.compile("\\(\\?<(.+?)>").matcher(pattern.pattern());
        while (matcher.find()) {
            namedGroups.add(matcher.group(1));
        }

        return namedGroups;
    }

    private record RegexGroup(String group, int start, int end) {
    }
}
