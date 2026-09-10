package com.epam.aidial.core.config.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;

public class ValidTimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        // restricted to real IANA region ids - a fixed offset like "+02:00" would also parse via
        // ZoneId.of, but the schedule must survive DST transitions, which a fixed offset cannot
        return ZoneId.getAvailableZoneIds().contains(value);
    }
}
