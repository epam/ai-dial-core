package com.epam.aidial.core.storage.data;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({})
public @interface ApiSubType {
    String discriminatorValue();
    Class<?> type();
}