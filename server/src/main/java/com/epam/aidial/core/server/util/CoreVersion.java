package com.epam.aidial.core.server.util;

import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Core build version, compared by its leading dotted numbers: {@code 0.50.0-SNAPSHOT} counts as {@code 0.50.0}, and
 * missing parts count as 0. A development build, which is {@code 0.0.0} or a version without a usable leading number
 * such as {@code undefined}, compares above every release and equal to any other development build.
 */
public final class CoreVersion implements Comparable<CoreVersion> {

    private static final Pattern LEADING_NUMBERS = Pattern.compile("^(\\d+(?:\\.\\d+)*)");

    private final String raw;
    private final int[] components;

    private CoreVersion(String raw, int[] components) {
        this.raw = raw;
        this.components = components;
    }

    public static CoreVersion parse(String raw) {
        return new CoreVersion(raw, stripTrailingZeros(leadingNumbers(raw)));
    }

    public boolean isDevelopment() {
        return components.length == 0;
    }

    @Override
    public int compareTo(CoreVersion other) {
        if (isDevelopment() || other.isDevelopment()) {
            return Boolean.compare(isDevelopment(), other.isDevelopment());
        }
        return Arrays.compare(components, other.components);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CoreVersion version && Arrays.equals(components, version.components);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(components);
    }

    @Override
    public String toString() {
        return String.valueOf(raw);
    }

    private static int[] leadingNumbers(String raw) {
        if (raw == null) {
            return new int[0];
        }
        Matcher matcher = LEADING_NUMBERS.matcher(raw);
        if (!matcher.find()) {
            return new int[0];
        }
        String[] parts = matcher.group(1).split("\\.");
        int[] numbers = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                numbers[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return new int[0];
        }
        return numbers;
    }

    private static int[] stripTrailingZeros(int[] numbers) {
        int length = numbers.length;
        while (length > 0 && numbers[length - 1] == 0) {
            length--;
        }
        return Arrays.copyOf(numbers, length);
    }
}
