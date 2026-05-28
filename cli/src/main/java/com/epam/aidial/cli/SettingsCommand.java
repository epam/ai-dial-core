package com.epam.aidial.cli;

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.UseDefaultConverter;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
        name = "settings",
        description = "Manage DIAL global settings (singleton).",
        mixinStandardHelpOptions = true,
        subcommands = {SettingsCommand.Get.class, SettingsCommand.Update.class,
                SettingsCommand.Delete.class, SettingsCommand.Validate.class,
                SettingsCommand.Promote.class, SettingsCommand.Diff.class}
)
public class SettingsCommand {

    static final String TYPE = "settings";
    static final String BUCKET = "platform";
    static final String KIND = "Settings";
    static final String CANONICAL_ID = TYPE + "/" + BUCKET + "/global";
    static final String SINGLETON_PATH = "/v1/" + TYPE + "/" + BUCKET + "/global";

    @ParentCommand
    DialCli parent;

    @Command(name = "get", description = "Get the effective global settings (no name argument).")
    static class Get implements Callable<Integer> {
        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", description = "Config source: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).", defaultValue = "api")
        ConfigSource source;

        @Override
        public Integer call() {
            return source == ConfigSource.FILE
                    ? EntityReader.readConfigFileSingleton(cmd.parent, spec)
                    : EntityReader.readSingleton(cmd.parent, spec, TYPE);
        }
    }

    @Command(name = "update",
            description = "Update global settings (PUT, upsert). The singleton has no 404 path.")
    static class Update implements Callable<Integer> {
        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--set", description = "Field override 'dot.path=value' (repeatable). Keys are dot-paths; values are JSON-coerced.",
                converter = {UseDefaultConverter.class, JsonNodeValueConverter.class})
        Map<String, JsonNode> sets;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency. Defaults to the GET response's ETag.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.updateEntity(cmd.parent, spec, TYPE, BUCKET, CANONICAL_ID, sets, ifMatch);
        }
    }

    @Command(name = "delete",
            description = "Clear the API blob for global settings (DELETE). Idempotent (always 204).")
    static class Delete implements Callable<Integer> {
        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--if-match", description = "ETag for optimistic concurrency.")
        String ifMatch;

        @Override
        public Integer call() {
            return EntityWriter.deleteEntity(cmd.parent, spec, TYPE, BUCKET, CANONICAL_ID, ifMatch);
        }
    }

    @Command(name = "validate",
            description = "Validate a proposed settings spec via POST /v1/admin/validate (no write).")
    static class Validate implements Callable<Integer> {
        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--from-file", required = true,
                description = "JSON or YAML file with the settings spec (.yaml/.yml parsed as YAML).")
        Path fromFile;
        @Option(names = "--template", description = "Template name from CLI profile to apply.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            return EntityWriter.validateEntity(cmd.parent, spec, TYPE, KIND, BUCKET, CANONICAL_ID, fromFile, template, params);
        }
    }

    @Command(name = "promote",
            description = "Promote global settings from one environment to another via POST /v1/admin/apply.")
    static class Promote implements Callable<Integer> {
        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--from", required = true, description = "Source environment.")
        String fromEnv;
        @Option(names = "--to", required = true, description = "Target environment.")
        String toEnv;
        @Option(names = "--template",
                description = "Template name from CLI profile, or 'auto' to reverse-match against the source entity.")
        String template;
        @Option(names = "--param", description = "Template parameter 'key=value' (repeatable).", converter = {UseDefaultConverter.class, ParamValueConverter.class})
        Map<String, Object> params = new HashMap<>();

        @Override
        public Integer call() {
            return EntityWriter.promoteEntity(cmd.parent, spec, TYPE, KIND, BUCKET, CANONICAL_ID,
                    fromEnv, toEnv, template, params);
        }
    }

    @Command(name = "diff",
            description = "Structural diff of global settings between two environments.")
    static class Diff implements Callable<Integer> {

        @ParentCommand
        SettingsCommand cmd;
        @Spec
        CommandSpec spec;
        @Option(names = "--source", required = true, description = "Source environment.")
        String sourceEnv;
        @Option(names = "--target", required = true, description = "Target environment.")
        String targetEnv;

        @Override
        public Integer call() {
            return EntityDiff.runSingleton(cmd.parent, spec, SINGLETON_PATH, sourceEnv, targetEnv);
        }
    }
}
