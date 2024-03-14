package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

public class LimitApiTest extends ResourceBaseTest {

    @Test
    public void testGetLimitStats_Success() {
        Response response = send(HttpMethod.GET, "/v1/deployments/test-model-v1/limits", null, null);
        verifyJson(response, 200, """
                {
                  "minuteTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayTokenStats": {
                    "total": %d,
                    "used": %d
                  },
                  "hourRequestStats": {
                    "total": %d,
                    "used": %d
                  },
                  "dayRequestStats": {
                    "total": %d,
                    "used": %d
                  }
                }
                """.formatted(Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0, Long.MAX_VALUE, 0));
    }

    @Test
    public void testGetLimitStats_UnknownModel() {
        Response response = send(HttpMethod.GET, "/v1/deployments/unknown-model/limits", null, null);
        verify(response, 404);
    }
}
