package com.epam.aidial.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UseDefaultConverter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "model",
        description = "Manage DIAL model entities.",
        mixinStandardHelpOptions = true,
        subcommands = {ModelCommand.Get.class, ModelCommand.List.class, ModelCommand.Add.class,
                ModelCommand.Update.class, ModelCommand.Delete.class, ModelCommand.Validate.class,
                ModelCommand.Promote.class, ModelCommand.Diff.class}
)
public class ModelCommand {

    static final String TYPE = "models";
    static final String BUCKET = "public";
    static final String KIND = "Model";
    static final String CANONICAL_PREFIX = TYPE + "/" + BUCKET + "/";

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get a single model by name (or canonical id).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Model name or canonical id (models/<bucket>/<name>).")
        String name;
        @Option(names = "--source", description = "Config source: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).", defaultValue = "api")
        ConfigSource source;

        @Override
        public Integer call() {
            return source == ConfigSource.FILE
                    ? EntityReader.readConfigFileEntity(model.parent, spec, TYPE, name)
                    : EntityReader.readEntity(model.parent, spec, TYPE, name);
        }
    }

    @Command(name = "list", description = "List models in the public bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", description = "Config source: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).", defaultValue = "api")
        ConfigSource source;

        @Override
        public Integer call() {
            return source == ConfigSource.FILE
                    ? EntityReader.listConfigFileEntities(model.parent, spec, TYPE)
                    : EntityReader.listEntities(model.parent, spec, TYPE);
        }
    }

    @Command(name = "add", description = "Create a model (PUT with If-None-Match: *). Fails with exit 5 if it already exists.")
    static class Add implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the model spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            EntityWriter.addEntity(model.parent, spec, TYPE, KIND, BUCKET, name, fromFile, template, params);
            return 0;
        }
    }

    @Command(name = "update",
            description = "Update a model (PUT). Fails with exit 4 if it does not exist, 6 on stale ETag.")
    static class Update implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--set", description = "Field override 'dot.path=value' (repeatable). Keys are dot-paths; values are JSON-coerced.",
                converter = {UseDefaultConverter.class, JsonNodeValueConverter.class})
        Map<String, JsonNode> sets;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency. Defaults to the GET response's ETag.")
        String ifMatch;

        @Override
        public Integer call() {
            EntityWriter.updateEntity(model.parent, spec, TYPE, BUCKET, name, sets, ifMatch);
            return 0;
        }
    }

    @Command(name = "delete", description = "Delete a model (DELETE). Fails with exit 4 if missing, 6 on stale ETag.")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Parameters(index = "0", description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency.")
        String ifMatch;

        @Override
        public Integer call() {
            EntityWriter.deleteEntity(model.parent, spec, TYPE, BUCKET, name, ifMatch);
            return 0;
        }
    }

    @Command(name = "validate",
            description = "Validate a proposed model spec via POST /v1/admin/validate (no write).")
    static class Validate implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--name", required = true,
                description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the model spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(model.parent, spec, TYPE, KIND, BUCKET, name, fromFile, template, params);
        }
    }

    @Command(name = "promote",
            description = "Promote a model from one environment to another via POST /v1/admin/apply.")
    static class Promote implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--from", required = true, description = "Source environment.")
        String fromEnv;
        @Option(names = "--to", required = true, description = "Target environment.")
        String toEnv;
        @Option(names = "--name", required = true,
                description = "Canonical id (models/public/<name>).")
        String name;
        @Option(names = "--template",
                description = "Template name from CLI profile, or 'auto' to reverse-match against the source entity.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(model.parent, spec, TYPE, KIND, BUCKET, name, fromEnv, toEnv, template, params);
        }
    }

    @Command(name = "diff",
            description = "Structural diff of a single model (with --name) or all models between two environments.")
    static class Diff implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", required = true, description = "Source environment.")
        String sourceEnv;
        @Option(names = "--target", required = true, description = "Target environment.")
        String targetEnv;
        @Option(names = "--name", description = "Optional canonical id (models/public/<name>) for a single-entity diff.")
        String name;

        @Override
        public Integer call() {
            return EntityDiff.run(model.parent, spec, TYPE, BUCKET, CANONICAL_PREFIX, sourceEnv, targetEnv, name);
        }
    }
}
