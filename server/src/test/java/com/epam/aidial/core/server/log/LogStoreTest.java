package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LogStoreTest {

    @Test
    void errorRowsAreOffByDefault() {
        assertFalse(((LogStore) row -> { }).isErrorRowsEnabled());
    }

    @Test
    void saveErrorResponseSavesAnErrorStatusWithTheSettingOn() {
        RecordingStore store = new RecordingStore(true);

        store.saveErrorResponse(context(502));

        // the row carries the status the response has
        assertEquals(1, store.saved.size());
        assertEquals(502, store.saved.get(0).getResponseStatusCode());
    }

    @Test
    void saveErrorResponseSavesTheEdgesOfTheErrorRange() {
        RecordingStore store = new RecordingStore(true);

        store.saveErrorResponse(context(199));
        store.saveErrorResponse(context(300));

        assertEquals(2, store.saved.size());
    }

    @Test
    void saveErrorResponseSkipsSuccessStatus() {
        RecordingStore store = new RecordingStore(true);

        store.saveErrorResponse(context(200));
        store.saveErrorResponse(context(204));
        store.saveErrorResponse(context(299));

        assertTrue(store.saved.isEmpty());
    }

    @Test
    void saveErrorResponseDoesNothingWithTheSettingOff() {
        RecordingStore store = new RecordingStore(false);

        store.saveErrorResponse(context(502));

        assertTrue(store.saved.isEmpty());
    }

    @Test
    void saveErrorRowSavesWhateverTheStatusWithTheSettingOn() {
        RecordingStore store = new RecordingStore(true);

        // a stream that broke after its headers has the 200 Core had set, and still gets its row
        store.saveErrorRow(context(200));

        assertEquals(1, store.saved.size());
        assertEquals(200, store.saved.get(0).getResponseStatusCode());
    }

    @Test
    void saveErrorRowDoesNothingWithTheSettingOff() {
        RecordingStore store = new RecordingStore(false);

        store.saveErrorRow(context(200));

        assertTrue(store.saved.isEmpty());
    }

    @Test
    void saveErrorRowSkipsRequestThatIsNotPost() {
        RecordingStore store = new RecordingStore(true);

        store.saveErrorRow(context(502, HttpMethod.GET));

        assertTrue(store.saved.isEmpty());
    }

    private static class RecordingStore implements LogStore {

        final List<AnalyticsLogContext> saved = new ArrayList<>();
        private final boolean errorRows;

        RecordingStore(boolean errorRows) {
            this.errorRows = errorRows;
        }

        @Override
        public void save(AnalyticsLogContext logContext) {
            saved.add(logContext);
        }

        @Override
        public boolean isErrorRowsEnabled() {
            return errorRows;
        }
    }

    private static ProxyContext context(int responseStatus) {
        return context(responseStatus, HttpMethod.POST);
    }

    private static ProxyContext context(int responseStatus, HttpMethod method) {
        ApiKeyData apiKeyData = new ApiKeyData();
        Key key = new Key();
        // a key with a project names the cost object, so building the row needs no config
        key.setProject("p");
        apiKeyData.setOriginalKey(key);
        ProxyContext context = new ProxyContext(null, mock(HttpServerRequest.class, RETURNS_DEEP_STUBS), apiKeyData, null,
                "11111111111111111111111111111111", "3333333333333333", "01");
        HttpServerRequest request = context.getRequest();
        when(request.version()).thenReturn(HttpVersion.HTTP_1_1);
        when(request.method()).thenReturn(method);
        when(request.response().getStatusCode()).thenReturn(responseStatus);
        return context;
    }
}
