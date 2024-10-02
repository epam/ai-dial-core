package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Application extends Deployment {

    private Function function;

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {

        private String id;
        private String sourceFolder;
        private String targetFolder;
        private Status status;

        public enum Status {
            CREATED, STARTING, STOPPING, STARTED, STOPPED, FAILED
        }
    }
}