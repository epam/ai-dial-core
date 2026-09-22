package com.epam.aidial.core.config;

import com.epam.aidial.core.config.validation.ValidTimezone;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * Deployment-wide anchor for the DAY/WEEK/MONTH fixed calendar rate-limit windows: what local time
 * of day a period resets at, in which timezone, and - for WEEK - which day it starts on. Omitting
 * this setting is equivalent to UTC/Mon/00:00, which reproduces the previous implicit UTC-midnight
 * day/month behavior and only newly defines a week start day.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RateLimitSchedule {
    @ValidTimezone
    private String timezone = "UTC";
    private WeekDay weekStartDay = WeekDay.Mon;
    @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$", message = "resetTime must be in HH:mm 24h format")
    private String resetTime = "00:00";
}
