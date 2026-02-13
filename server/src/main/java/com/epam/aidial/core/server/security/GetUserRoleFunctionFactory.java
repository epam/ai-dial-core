package com.epam.aidial.core.server.security;

import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
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
                    List<String> groups = extractGroups(json);
                    promise.complete(groups);
                } catch (Throwable e) {
                    log.debug("Exception occurred at processing google group response", e);
                    promise.fail(e);
                }
                return null;
            }).onFailure(promise::fail);
        }));
        return promise.future();
    }

    @VisibleForTesting
    static List<String> extractGroups(JsonObject response) {
        JsonArray memberships = response.getJsonArray("memberships");
        List<String> groups = new ArrayList<>();
        if (memberships != null) {
            for (int i = 0; i < memberships.size(); i++) {
                Optional.ofNullable(memberships.getJsonObject(i))
                        .map(membership -> membership.getJsonObject("groupKey"))
                        .map(groupKey -> groupKey.getString("id")).ifPresent(groups::add);
            }
        }
        return groups;
    }
}
