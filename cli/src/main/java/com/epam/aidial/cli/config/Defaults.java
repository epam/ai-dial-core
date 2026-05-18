package com.epam.aidial.cli.config;

import com.epam.aidial.cli.OutputFormat;
import lombok.Data;

@Data
public class Defaults {
    private OutputFormat output;
    private String env;
}
