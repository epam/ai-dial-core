package com.epam.aidial.core.server.service.codeinterpreter;

import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteResponse;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFiles;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSession;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.mime.MultipartEntityBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.HttpEntities;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class CodeInterpreterClient {

    // Vertx HttpClient does not support multipart upload, Vertx WebClient supports only Buffer as body for multipart upload
    private final HttpClient client = HttpClients.createDefault();
    private final long responseTimeout;

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
        HttpPost post = new HttpPost(session.getDeploymentUrl() + "/upload_file");
        post.setConfig(createRequestConfig());
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
        HttpPost post = new HttpPost(session.getDeploymentUrl() + "/download_file");
        post.setConfig(createRequestConfig());
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
                long size = Long.parseLong(response.getHeader(HttpHeaders.CONTENT_LENGTH).getValue());
                InputStream stream = entity.getContent();

                consumer.apply(stream, size)
                        .onSuccess(result::complete)
                        .onFailure(result::completeExceptionally);

                return result.get(responseTimeout, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                EntityUtils.consumeQuietly(entity);
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to download file: " + path);
            }
        });
    }

    @SneakyThrows
    private <R> R execute(CodeInterpreterSession session, String path, Object requestPayload, Class<R> responseType) {
        HttpPost post = new HttpPost(session.getDeploymentUrl() + path);
        post.setConfig(createRequestConfig());
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

    private RequestConfig createRequestConfig() {
        return RequestConfig.custom().setResponseTimeout(responseTimeout, TimeUnit.MILLISECONDS).build();
    }

    public interface DownloadFileFunction<R> {
        Future<R> apply(InputStream stream, long size) throws Throwable;
    }
}