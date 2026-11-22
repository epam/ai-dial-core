package com.epam.aidial.core.plugin.samples;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Origin plugins of type {@code forward}: each request goes on to one deployment, in process, through
 * {@link HostDelegate#call}. Settings: {@code {"deployment": "<name>"}}.
 * <ul>
 *   <li>A deployments-POST action goes to the same action of that deployment.</li>
 *   <li>Chat completions, Responses and Messages keep their path, with {@code model} set to that deployment.</li>
 *   <li>Anything else fails, a call reaching the create operations only: the item operations of Responses and
 *   Messages {@code count_tokens}.</li>
 * </ul>
 * The call's answer is the plugin's answer, so a cancel of it reaches the call.
 */
public class ForwardOriginPluginProvider implements PluginProvider {

    public static final String TYPE = "forward";

    private static final Pattern DEPLOYMENT_ACTION = Pattern.compile("^/+openai/deployments/.+?/(completions|chat/completions|embeddings)$");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        JsonNode deployment = settings.get("deployment");
        return deployment != null && deployment.isTextual() && !deployment.asText().isBlank()
                ? List.of()
                : List.of(new Problem("deployment", "deployment must be the name of the deployment to forward to"));
    }

    @Override
    public Plugin create(ObjectNode settings, Vertx vertx) {
        String deployment = settings.get("deployment").asText();
        return (request, host) -> forward(request, host, deployment);
    }

    private static Future<Response> forward(Request request, HostDelegate host, String deployment) {
        ObjectNode body = request.bodyTree();
        if (body == null) {
            return Future.failedFuture(new IllegalArgumentException("forward serves the create operations only, not " + request.uri()));
        }
        return host.call(new Call(target(request.uri(), deployment, body), request.headers(), body));
    }

    /**
     * The path and query of the call for a request to {@code uri}. Sets {@code model} in {@code body} when the path
     * does not name the deployment.
     */
    static String target(String uri, String deployment, ObjectNode body) {
        int question = uri.indexOf('?');
        String path = question < 0 ? uri : uri.substring(0, question);
        String query = question < 0 ? "" : uri.substring(question);
        Matcher action = DEPLOYMENT_ACTION.matcher(path);
        if (action.matches()) {
            return "/openai/deployments/" + deployment + "/" + action.group(1) + query;
        }
        body.put("model", deployment);
        return uri;
    }
}
