package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.deltix.gflog.api.LogEntry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Log thread throughput per settings combination: {@code AIDIAL_BENCHMARK=true ./gradlew :server:test --tests "*GfLogStoreBenchmarkTest" -i}.
 */
@EnabledIfEnvironmentVariable(named = "AIDIAL_BENCHMARK", matches = "true")
public class GfLogStoreBenchmarkTest {

    private static final int MB = 1024 * 1024;

    @Test
    public void benchmark() throws Exception {
        Map<String, AnalyticsLogContext> bodies = new LinkedHashMap<>();
        bodies.put("2 MB image request", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, imageRequest(1, 2 * MB), null, null));
        bodies.put("40 MB multi-file request", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, imageRequest(10, 4 * MB), null, null));
        bodies.put("streamed audio response", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, imageRequest(0, 0), audioStream(500, 4096),
                "{\"choices\":[{\"index\":0,\"message\":{\"audio\":{\"data\":\"" + "A".repeat(500 * 4096) + "\"}}}]}"));
        bodies.put("200 KB text request", context(InterfaceType.OPENAI_CHAT_COMPLETIONS, textRequest(200 * 1024), null, null));

        Map<String, JsonObject> combinations = new LinkedHashMap<>();
        combinations.put("defaults (today)", new JsonObject());
        combinations.put("collectInlineFiles=false", new JsonObject().put("collectInlineFiles", false));
        combinations.put("collectTruncatedInlineFiles=false", new JsonObject().put("collectTruncatedInlineFiles", false));
        combinations.put("collectInlineFileIndex=true", new JsonObject().put("collectInlineFileIndex", true));
        combinations.put("removal + index", new JsonObject().put("collectInlineFiles", false).put("collectInlineFileIndex", true));

        LogEntry sink = (LogEntry) Proxy.newProxyInstance(LogEntry.class.getClassLoader(), new Class<?>[] {LogEntry.class},
                (proxy, method, args) -> proxy);
        System.out.printf("%-26s %-36s %12s%n", "body", "settings", "ms/entry");
        for (Map.Entry<String, AnalyticsLogContext> body : bodies.entrySet()) {
            for (Map.Entry<String, JsonObject> combination : combinations.entrySet()) {
                GfLogStore store = new GfLogStore(AnalyticsSettings.from(combination.getValue()));
                for (int i = 0; i < 20; i++) {
                    store.append(body.getValue(), sink);
                }
                int runs = 50;
                long start = System.nanoTime();
                for (int i = 0; i < runs; i++) {
                    store.append(body.getValue(), sink);
                }
                double ms = (System.nanoTime() - start) / 1e6 / runs;
                System.out.printf("%-26s %-36s %12.2f%n", body.getKey(), combination.getKey(), ms);
            }
        }
    }

    private static AnalyticsLogContext context(InterfaceType type, String request, String response, String assembled) {
        return AnalyticsLogContext.builder()
                .interfaceType(type)
                .requestBody(Buffer.buffer(request))
                .responseBody(response == null ? null : Buffer.buffer(response))
                .assembledStreamingResponse(assembled)
                .build();
    }

    private static String imageRequest(int images, int imageBytes) {
        StringBuilder body = new StringBuilder("{\"messages\":[{\"role\":\"user\",\"content\":[");
        for (int i = 0; i < images; i++) {
            body.append("{\"type\":\"text\",\"text\":\"").append("describe the next image ".repeat(400)).append("\"},")
                    .append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,").append("A".repeat(imageBytes)).append("\"}},");
        }
        return body.append("{\"type\":\"text\",\"text\":\"end\"}]}]}").toString();
    }

    private static String textRequest(int bytes) {
        return "{\"messages\":[{\"role\":\"user\",\"content\":\"" + "lorem ipsum ".repeat(bytes / 12) + "\"}]}";
    }

    private static String audioStream(int deltas, int deltaBytes) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < deltas; i++) {
            body.append("data: {\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"data\":\"").append("A".repeat(deltaBytes)).append("\"}}}]}\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }
}
