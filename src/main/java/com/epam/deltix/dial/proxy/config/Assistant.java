package com.epam.deltix.dial.proxy.config;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
public class Assistant extends Deployment {
    private String prompt;
    private List<String> addons = List.of();
}