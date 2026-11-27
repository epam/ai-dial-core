package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Budget based spending limits. {@code defaultCostObject} is the cost object of a call that carries no project;
 * it becomes a path element of the budget counters, so it follows the entity name pattern without {@code %}.
 * {@code autoBudgetSelection} lets the priority walk pick the payer of a call that indicates no budget and has no
 * default budget in effect; {@code budgetMandatory} refuses a call that indicates no budget.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SpendingLimits {
    @NotNull
    private SpendingLimitMode mode = SpendingLimitMode.OFF;
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9._:@\\[\\]()-]+$", message = "defaultCostObject must not be empty or contain '/' or '%'")
    private String defaultCostObject = "ORG";
    private boolean autoBudgetSelection = true;
    private boolean budgetMandatory;
}
