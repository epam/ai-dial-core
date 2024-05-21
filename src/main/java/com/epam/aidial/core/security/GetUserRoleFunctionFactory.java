package com.epam.aidial.core.security;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.http.HttpHeaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class GetUserRoleFunctionFactory {

    private static final String GOOGLE_IAM_GROUPS_ENDPOINT = "https://content-cloudidentity.googleapis.com/v1/groups/-/memberships:searchDirectGroups?query=member_key_id=='%s'";

    private final HttpClient client;
    private final Map<String, GetUserRoleFn> roleFnMapping = new HashMap<>();

    public GetUserRoleFunctionFactory(HttpClient client) {
        this.client = client;
        roleFnMapping.put("fn:getGoogleWorkspaceGroups", this::getGoogleWorkspaceGroups);
    }

    public GetUserRoleFn getUserRoleFn(String fnName) {
        return roleFnMapping.get(fnName);
    }

    private Future<List<String>> getGoogleWorkspaceGroups(String accessToken, Map<String, Object> claims) {
        String userEmail = (String) claims.get("email");
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(GOOGLE_IAM_GROUPS_ENDPOINT.formatted(userEmail))
                .addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .setMethod(HttpMethod.GET);
        Promise<List<String>> promise = Promise.promise();
        client.request(options).onFailure(promise::fail).onSuccess(request -> request.send().onFailure(promise::fail).onSuccess(response -> {
            if (response.statusCode() != 200) {
                promise.fail(String.format("Request getGoogleWorkspaceGroups failed with http code %d", response.statusCode()));
                return;
            }
            response.body().map(body -> {
                try {
                    JsonObject json = body.toJsonObject();
                    JsonArray memberships = json.getJsonArray("memberships");
                    List<String> groups = new ArrayList<>();
                    for (int i = 0; i < memberships.size(); i++) {
                        JsonObject membership = memberships.getJsonObject(i);
                        String group = membership.getJsonObject("groupKey").getString("id");
                        groups.add(group);
                    }
                    promise.complete(groups);
                } catch (Throwable e) {
                    promise.fail(e);
                }
                return null;
            }).onFailure(promise::fail);
        }));
        return promise.future();
    }
}
