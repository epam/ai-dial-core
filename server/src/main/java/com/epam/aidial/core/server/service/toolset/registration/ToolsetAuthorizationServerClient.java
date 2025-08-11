package com.epam.aidial.core.server.service.toolset.registration;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.HttpEntities;

@Slf4j
@RequiredArgsConstructor
public class ToolsetAuthorizationServerClient {

    private final HttpClient client = HttpClients.createDefault();
    private final long responseTimeout;

    public  <R> R executeGet(String url, Class<R> responseType) {
        try {
            HttpGet get = new HttpGet(url);
            get.setConfig(createRequestConfig());
            return execute(get, responseType);
        } catch (IOException e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }

    }

    public  <R> R executePost(String url, Object requestPayload, Class<R> responseType) {
        try {
            HttpPost post = new HttpPost(url);
            post.setConfig(createRequestConfig());
            String stringRequestPayload = ProxyUtil.convertToString(requestPayload);
            assert stringRequestPayload != null;
            post.setEntity(HttpEntities.create(stringRequestPayload, ContentType.APPLICATION_JSON));
            return execute(post, responseType);
        } catch (IOException e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private <R> R execute(HttpUriRequest request, Class<R> responseType) throws IOException {
        return client.execute(request, response -> {
            int status = response.getCode();
            String body = EntityUtils.toString(response.getEntity());

            if (status != 200 && status != 201) {
                log.error("Error executing post: {}", response);
                throw new HttpException(status, body);
            }

            return ProxyUtil.convertToObject(body, responseType);
        });
    }

    private RequestConfig createRequestConfig() {
        return RequestConfig.custom().setResponseTimeout(responseTimeout, TimeUnit.SECONDS).build();
    }
}
