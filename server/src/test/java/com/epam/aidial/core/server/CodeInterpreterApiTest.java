package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class CodeInterpreterApiTest extends ResourceBaseTest {

    private TestWebServer webServer;

    @BeforeEach
    void initWebServer() {
        webServer = new TestWebServer(17321);
    }

    @AfterEach
    void destroyDeploymentService() {
        try (TestWebServer server = webServer) {
            // closing
        }
    }

    @Test
    void testStatefulWorkflow() {
        webServer.map(HttpMethod.POST, "/v1/deployment/0124", 200, """
                event: result
                data: {"url":"http://localhost:17321"}""");
        Response response = send(HttpMethod.POST, "/v1/ops/code_interpreter/open_session", null, """
                {}""");
        verifyJson(response, 200, """
                {"sessionId":"0123"}""");

        webServer.map(HttpMethod.POST, "/execute_code", 200, """
                {"status":"SUCCESS","stdout":"","stderr":"","result":{"text/plain":"3"},"display":[]}""");
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/execute_code", null, """
                {"sessionId":"0123","code":"1+2"}""");
        verifyJson(response, 200, """
                {"status":"SUCCESS","stdout":"","stderr":"","result":{"text/plain":"3"},"display":[]}""");

        webServer.map(HttpMethod.POST, "/list_files", 200, """
                {"files": []}""");
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/list_files", null, """
                {"sessionId":"0123"}
                """);
        verifyJson(response, 200, """
                {"files": []}""");

        String content = "1".repeat(16 * 1024 * 1024);
        webServer.map(HttpMethod.POST, "/upload_file", 200, """
                {"path": "/mnt/data/file.txt","size": 16777216}""");
        response = upload(HttpMethod.POST, "/v1/ops/code_interpreter/upload_file", "session_id=0123", content);
        verifyJson(response, 200, """
                {"path": "/mnt/data/file.txt","size": 16777216}""");

        webServer.map(HttpMethod.POST, "/download_file", 200, content);
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/download_file", null, """
                {"sessionId":"0123","path":"file.txt"}""");
        verify(response, 200, content);

        webServer.map(HttpMethod.POST, "/list_files", 200, """
                {"files": [{"path": "/mnt/data/file.txt","size": 16777216}]}""");
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/list_files", null, """
                {"sessionId":"0123"}""");
        verifyJson(response, 200, """
                {"files": [{"path": "/mnt/data/file.txt","size": 16777216}]}""");

        content += "2";
        upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/file2.txt", null, content);
        verify(response, 200);

        webServer.map(HttpMethod.POST, "/upload_file", 200, """
                {"path": "/mnt/data/file2.txt","size": 16777217}""");
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/transfer_input_file", null, """
                {"sessionId":"0123","sourceUrl":"files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/file2.txt","targetPath":"file2.txt"}""");
        verifyJson(response, 200, """
                {"path": "/mnt/data/file2.txt","size": 16777217}""");

        webServer.map(HttpMethod.POST, "/download_file", 200, content);
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/transfer_output_file", null, """
                {"sessionId":"0123","sourcePath":"file2.txt","targetUrl":"files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/file3.txt"}""");
        verify(response, 200);

        response = send(HttpMethod.GET, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/file3.txt", null, "");
        verify(response, 200, content);

        webServer.map(HttpMethod.DELETE, "/v1/deployment/0124", 200, """
                event: result
                data: {"deleted":true}""");
        response = send(HttpMethod.POST, "/v1/ops/code_interpreter/close_session", null, """
                {"sessionId":"0123"}""");
        verifyJson(response, 200, """
                {"sessionId":"0123"}""");
    }

    @Test
    void testStatelessWorkflow() {
        String inputContent = "1".repeat(1024);
        String outputContent = "2".repeat(2048);

        upload(HttpMethod.PUT, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/input-file.txt", null, inputContent);

        webServer.map(HttpMethod.POST, "/v1/deployment/0124", 200, """
                event: result
                data: {"url":"http://localhost:17321"}""");

        webServer.map(HttpMethod.POST, "/upload_file", 200, """
                {"path": "/mnt/data/input-file.txt","size": 1024}""");

        webServer.map(HttpMethod.POST, "/execute_code", 200, """
                {"status":"SUCCESS","stdout":"","stderr":"","result":{"text/plain":"3"},"display":[]}""");

        webServer.map(HttpMethod.POST, "/download_file", 200, outputContent);

        webServer.map(HttpMethod.DELETE, "/v1/deployment/0124", 200, """
                event: result
                data: {"deleted":true}""");


        Response response = send(HttpMethod.POST, "/v1/ops/code_interpreter/execute_code", null, """
                {
                  "code":"1+2",
                  "inputFiles":[{"sourceUrl":"files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/input-file.txt","targetPath":"input-file.txt"}],
                  "outputFiles":[{"targetUrl":"files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/output-file.txt","sourcePath":"output-file.txt"}]
                }""");
        verifyJson(response, 200, """
                {"status":"SUCCESS","stdout":"","stderr":"","result":{"text/plain":"3"},"display":[]}""");

        response = send(HttpMethod.GET, "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/output-file.txt", null, "");
        verify(response, 200, outputContent);
    }
}
