package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.limiter.budget.BudgetCheck;
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
    void saveErrorResponseKeepsBodiesOnlyForStatusTheBodyMayCause() {
        RecordingStore store = new RecordingStore(true);

        for (int status : new int[] {400, 500, 402, 403, 404, 415, 422, 429, 502, 503, 504}) {
            store.saveErrorResponse(context(status));
        }

        assertEquals(List.of(false, false, true, true, true, true, true, true, true, true, true),
                store.saved.stream().map(AnalyticsLogContext::isBodiesOmitted).toList());
    }

    @Test
    void saveErrorResponseDropsBodiesOfSpendingLimitRefusal() {
        RecordingStore store = new RecordingStore(true);
        // /openai/v1/responses answers the refusal with a 400; shadow mode does not refuse
        ProxyContext refused = context(400);
        refused.setBudgetCheck(BudgetCheck.started(SpendingLimitMode.ENFORCE, "p").noBudget());
        ProxyContext exhausted = context(400);
        exhausted.setBudgetCheck(BudgetCheck.started(SpendingLimitMode.ENFORCE, "p").exhausted());
        ProxyContext shadow = context(400);
        shadow.setBudgetCheck(BudgetCheck.started(SpendingLimitMode.SHADOW, "p").noBudget());

        store.saveErrorResponse(refused);
        store.saveErrorResponse(exhausted);
        store.saveErrorResponse(shadow);

        assertEquals(List.of(true, true, false), store.saved.stream().map(AnalyticsLogContext::isBodiesOmitted).toList());
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
        // it keeps its bodies, as the row of the relay it replaces would
        assertFalse(store.saved.get(0).isBodiesOmitted());
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
