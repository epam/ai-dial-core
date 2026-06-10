package com.epam.aidial.cli.command;

import com.epam.aidial.cli.DialCli;
import com.epam.aidial.cli.command.output.EntityRenderer;
import com.epam.aidial.cli.exception.CliException;
import com.epam.aidial.cli.service.EntityReader;
import com.epam.aidial.cli.service.EnvResolver;
import com.epam.aidial.cli.service.OutputFormatResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import picocli.CommandLine.Model.CommandSpec;

final class EntityReadOps {

    private EntityReadOps() {
    }

    static void readEntity(DialCli parent, CommandSpec spec, String type, String name) {
        EnvResolver.ResolvedEnv env = EnvResolver.resolveEnv(parent.toCliOptionsDto());
        try {
            JsonNode response = EntityReader.getEntity(env, type, name);
            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(parent.toCliOptionsDto()));
            spec.commandLine().getOut().println(renderer.renderSingle(response, type));
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    static void listEntities(DialCli parent, CommandSpec spec, String type) {
        EnvResolver.ResolvedEnv env = EnvResolver.resolveEnv(parent.toCliOptionsDto());
        try {
            ArrayNode entries = EntityReader.getEntities(env, type, spec.commandLine().getErr());
            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(parent.toCliOptionsDto()));
            spec.commandLine().getOut().println(renderer.renderList(entries, type));
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }

    static void readSingleton(DialCli parent, CommandSpec spec, String type, String name) {
        EnvResolver.ResolvedEnv env = EnvResolver.resolveEnv(parent.toCliOptionsDto());
        try {
            ArrayNode entries = EntityReader.getSingleton(env, type, name);
            EntityRenderer renderer = EntityRenderer.of(OutputFormatResolver.resolve(parent.toCliOptionsDto()));
            spec.commandLine().getOut().println(renderer.renderList(entries, type));
        } catch (JsonProcessingException e) {
            throw CliException.jsonProcessing("Failed to parse response: " + e.getMessage());
        }
    }
}
