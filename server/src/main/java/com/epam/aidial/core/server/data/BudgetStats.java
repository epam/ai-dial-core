package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * The caller's spend against one budget that applies to them in a cost object; a cost object can hold several. A window without a limit
 * carries the unlimited sentinel in its {@code total}.
 */
@Data
public class BudgetStats {
    private String costObject;
    /** The path of the budget's policy. */
    private String policy;
    /** The priority of the budget's policy, lower first; absent when the policy sets none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer priority;
    private CostItemLimitStats day;
    private CostItemLimitStats month;
}
