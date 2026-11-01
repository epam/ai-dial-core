package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Amount per calendar window; a window left unset is not limited.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PolicyAmounts {
    private BigDecimal day;
    private BigDecimal month;
}
