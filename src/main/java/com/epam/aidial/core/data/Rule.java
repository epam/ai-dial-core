package com.epam.aidial.core.data;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.Data;

import java.util.List;

@Data
public class Rule {

    public static final TypeReference<List<Rule>> LIST_TYPE =  new TypeReference<>() {
    };

    Function function;
    String source;
    List<String> targets;

    public enum Function {
        TRUE, FALSE, EQUAL, CONTAIN, REGEX,
    }
}