package com.epam.aidial.core.server.service.codeinterpreter;

import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteResponse;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFiles;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSession;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.HttpEntities;
import org.apache.hc.core5.http.message.BasicHttpRequest;

import java.io.InputStream;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

public class CodeInterpreterClient {

    private static final String PROXY_TARGET = "X-DIAL-PROXY-TARGET";
    private static final String SESSION_ID = "X-DIAL-SESSION-ID";

    // Vertx HttpClient does not support multipart upload, Vertx WebClient supports only Buffer as body for multipart upload
    private final HttpClient client;

    private final String proxyUrl;
    private final long timeout;

    public CodeInterpreterClient(String proxyUrl, long timeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(timeout, TimeUnit.MILLISECONDS)
                .setResponseTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();

        this.proxyUrl = proxyUrl;
        this.timeout = timeout;
        this.client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableContentCompression()
                .build();
    }

    CodeInterpreterExecuteResponse executeCode(CodeInterpreterSession session, String code) {
        Map<String, String> body = Map.of("code", code);
        return execute(session, "/execute_code", body, CodeInterpreterExecuteResponse.class);
    }

    CodeInterpreterFiles listFiles(CodeInterpreterSession session) {
        Map<Object, Object> body = Map.of();
        return execute(session, "/list_files", body, CodeInterpreterFiles.class);
    }

    @SneakyThrows
    CodeInterpreterFile uploadFile(CodeInterpreterSession session, InputStream source, String target) {
        HttpPost post = new HttpPost(createSessionUrl(session, "/upload_file"));
        addSessionHeaders(session, post);

        post.setEntity(MultipartEntityBuilder.create()
                .addBinaryBody("file", source, ContentType.APPLICATION_OCTET_STREAM, target)
                .build());

        return client.execute(post, response -> {
            int status = response.getCode();
            String body = EntityUtils.toString(response.getEntity());

            if (status != 200) {
                throw new HttpException(status, body);
            }

            return ProxyUtil.convertToObject(body, CodeInterpreterFile.class);
        });
    }

    @SneakyThrows
    <R> R downloadFile(CodeInterpreterSession session, String path, DownloadFileFunction<R> consumer) {
        HttpPost post = new HttpPost(createSessionUrl(session, "/download_file"));
        addSessionHeaders(session, post);
        post.setEntity(HttpEntities.create(ProxyUtil.convertToString(Map.of("path", path)), ContentType.APPLICATION_JSON));

        return client.execute(post, response -> {
            int status = response.getCode();
            HttpEntity entity = response.getEntity();

            if (status != 200) {
                String body = EntityUtils.toString(entity);
                throw new HttpException(status, body);
            }

            try {
                CompletableFuture<R> result = new CompletableFuture<>();
                Long size = getContentLength(response);
                InputStream stream = entity.getContent();

                consumer.apply(stream, size)
                        .onSuccess(result::complete)
                        .onFailure(result::completeExceptionally);

                return result.get(timeout, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                EntityUtils.consumeQuietly(entity);
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download file: " + path);
            }
        });
    }

    @SneakyThrows
    private <R> R execute(CodeInterpreterSession session, String path, Object requestPayload, Class<R> responseType) {
        HttpPost post = new HttpPost(createSessionUrl(session, path));
        addSessionHeaders(session, post);
        post.setEntity(HttpEntities.create(ProxyUtil.convertToString(requestPayload), ContentType.APPLICATION_JSON));

        return client.execute(post, response -> {
            int status = response.getCode();
            String body = EntityUtils.toString(response.getEntity());

            if (status != 200) {
                throw new HttpException(status, body);
            }

            return ProxyUtil.convertToObject(body, responseType);
        });
    }

    private String createSessionUrl(CodeInterpreterSession session, String path) {
        if (session.getDeploymentType() == CodeInterpreterSession.DeploymentType.SESSION) {
            Objects.requireNonNull(proxyUrl, "No proxy url");
            return proxyUrl + path;
        }

        return session.getDeploymentUrl() + path;
    }

    private void addSessionHeaders(CodeInterpreterSession session, BasicHttpRequest request) {
        if (session.getDeploymentType() == CodeInterpreterSession.DeploymentType.SESSION) {
            Objects.requireNonNull(proxyUrl, "No proxy url");
            request.setHeader(PROXY_TARGET, session.getDeploymentUrl());
            request.setHeader(SESSION_ID, session.getDeploymentId());
        }
    }

    private static Long getContentLength(ClassicHttpResponse response) {
        try {
            Header header = response.getHeader(HttpHeaders.CONTENT_LENGTH);
            if (header == null) {
                return null;
            }
            String text = header.getValue();
            long value = Long.parseLong(text);

            return (value >= 0) ? value : null;
        } catch (Exception e) {
            return null;
        }
    }

    public interface DownloadFileFunction<R> {
        Future<R> apply(InputStream stream, @Nullable Long size) throws Throwable;
    }
}