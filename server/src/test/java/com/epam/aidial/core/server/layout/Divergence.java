package com.epam.aidial.core.server.layout;

import com.epam.aidial.core.server.layout.CorpusRunner.StepKey;

/**
 * One observable difference between the two runs, addressed finely enough that an expectations entry can name
 * it without covering anything else: {@code field} is {@code status}, {@code body}, or {@code header:<name>}.
 */
public record Divergence(StepKey step, String field, String legacy, String tenantRooted) {

    public String describe() {
        return step + " [" + field + "]\n"
                + "  legacy:       " + legacy + "\n"
                + "  tenantRooted: " + tenantRooted;
    }
}
