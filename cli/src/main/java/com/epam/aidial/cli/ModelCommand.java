package com.epam.aidial.cli;

import com.epam.aidial.cli.http.CliHttpClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

import java.nio.file.Path;
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

        @Override
        public Integer call() {
            return EntityReader.readEntity(model.parent, spec, "models", name);
        }
    }

    @Command(name = "list", description = "List models in the public bucket.")
    static class List implements Callable<Integer> {
        @ParentCommand
        ModelCommand model;
        @Spec
        CommandSpec spec;

        @Override
        public Integer call() {
            return EntityReader.listEntities(model.parent, spec, "models");
        }
    }

    @Command(name = "add", description = "Create a model (POST). Fails with exit 5 if it already exists.")
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
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).")
        java.util.List<String> params;

        @Override
        public Integer call() {
            return EntityWriter.addEntity(model.parent, spec, "models", "Model", "public", name, fromFile, template, params);
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
        @Option(names = "--set", description = "Field override 'path=value' (repeatable). Dotted paths nest; values are JSON-coerced.")
        java.util.List<String> sets;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency. Defaults to the GET response's ETag.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.updateEntity(model.parent, spec, "models", name, sets, ifMatch);
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
            return EntityWriter.deleteEntity(model.parent, spec, "models", name, ifMatch);
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
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).")
        java.util.List<String> params;

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(model.parent, spec, "models", "Model", "public", name, fromFile, template, params);
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

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(model.parent, spec, "models", "Model", name, fromEnv, toEnv);
        }
    }

    @Command(name = "diff",
            description = "Structural diff of a single model (with --name) or all models between two environments.")
    static class Diff implements Callable<Integer> {
        private static final ObjectMapper JSON = new ObjectMapper();

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
            EntityReader.ResolvedEnv source = EntityReader.resolveEnv(model.parent, spec, sourceEnv);
            if (source == null) {
                return 2;
            }
            EntityReader.ResolvedEnv target = EntityReader.resolveEnv(model.parent, spec, targetEnv);
            if (target == null) {
                return 2;
            }
            String path;
            boolean isList;
            if (name == null || name.isBlank()) {
                path = "/v1/models/public/?limit=100";
                isList = true;
            } else {
                String prefix = "models/public/";
                if (!name.startsWith(prefix) || name.length() == prefix.length() || name.indexOf('/', prefix.length()) >= 0) {
                    spec.commandLine().getErr().println(
                            "--name must be a canonical id 'models/public/<name>'; got '" + name + "'.");
                    return 2;
                }
                path = "/v1/" + name;
                isList = false;
            }
            JsonNode sourceTree = fetchOrAbsent(source, path, isList);
            if (sourceTree == null) {
                return 1;
            }
            JsonNode targetTree = fetchOrAbsent(target, path, isList);
            if (targetTree == null) {
                return 1;
            }
            java.util.List<JsonDiff.Change> changes = JsonDiff.diff(sourceTree, targetTree);
            if (changes.isEmpty()) {
                spec.commandLine().getOut().println("No differences.");
                return 0;
            }
            for (JsonDiff.Change c : changes) {
                spec.commandLine().getOut().println(c);
            }
            return 0;
        }

        private JsonNode fetchOrAbsent(EntityReader.ResolvedEnv env, String path, boolean isList) {
            CliHttpClient.Response resp;
            try {
                resp = new CliHttpClient(env.apiUrl(), env.apiKey()).get(path);
            } catch (CliHttpClient.NetworkException e) {
                spec.commandLine().getErr().println(env.envName() + ": " + e.getMessage());
                return null;
            }
            if (resp.status() == 404 && !isList) {
                return JSON.createObjectNode();
            }
            if (resp.status() >= 300) {
                spec.commandLine().getErr().println(env.envName() + ": HTTP " + resp.status() + " " + resp.body());
                return null;
            }
            try {
                JsonNode body = JSON.readTree(resp.body());
                if (!isList) {
                    return body;
                }
                JsonNode items = body.get("items");
                if (items == null || !items.isArray()) {
                    spec.commandLine().getErr().println(env.envName() + ": unexpected listing shape (missing 'items').");
                    return null;
                }
                JsonNode hasMore = body.get("hasMore");
                if (hasMore != null && hasMore.asBoolean()) {
                    spec.commandLine().getErr().println("[warn] " + env.envName() + ": result truncated at 100 items.");
                }
                ObjectNode keyed = JSON.createObjectNode();
                for (JsonNode item : items) {
                    JsonNode itemName = item.get("name");
                    if (itemName == null || itemName.isNull()) {
                        continue;
                    }
                    keyed.set(itemName.asText(), item);
                }
                return keyed;
            } catch (JsonProcessingException e) {
                spec.commandLine().getErr().println(env.envName() + ": failed to parse response: " + e.getMessage());
                return null;
            }
        }
    }
}
