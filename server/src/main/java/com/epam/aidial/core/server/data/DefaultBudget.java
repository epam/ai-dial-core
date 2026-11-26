package com.epam.aidial.core.server.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The body of {@code /v1/user/budgets/default} and of the stored record.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DefaultBudget {
    /**
     * The budget id, {@code <costObject>/<id>}, or {@code null} when no default is set.
     */
    private String budget;
}
