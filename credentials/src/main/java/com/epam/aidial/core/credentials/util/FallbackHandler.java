package com.epam.aidial.core.credentials.util;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Slf4j
@UtilityClass
public class FallbackHandler {

    /**
     * Executes calls with fallback logic, trying multiple endpoints.
     *
     * @param endpoints Set of endpoints to try sequentially.
     * @param fetchFunction Function to execute the call (e.g., HTTP GET/POST logic).
     * @param validationFunction Function to perform result validation
     * @param <T> Type of the result expected from the fetchFunction.
     * @return Result of the successful call, or null if all attempts fail.
     */
    public static <T> T executeWithFallback(Set<String> endpoints,
                                            Function<String, T> fetchFunction,
                                            BiConsumer<String, T> validationFunction) {
        for (String endpoint : endpoints) {
            try {
                log.debug("Trying endpoint: {}", endpoint);
                T result = fetchFunction.apply(endpoint);
                if (result != null) {
                    log.debug("Successfully fetched data from endpoint: {}", endpoint);
                    validationFunction.accept(endpoint, result);
                    return result;
                }
            } catch (HttpException e) {
                log.debug("Failed to fetch data from endpoint: {}. HttpStatus: {}", endpoint, e.getStatus());
                if (shouldFallback(e)) {
                    log.debug("Proceeding to next fallback for endpoint: {}", endpoint);
                } else {
                    throw e;
                }
            }
        }
        log.debug("All endpoints failed. Returning null.");
        return null;
    }

    /**
     * Determines if fallback should occur based on the exception.
     *
     * @param httpException Exception to evaluate.
     * @return true if fallback should proceed, false otherwise.
     */
    private static boolean shouldFallback(HttpException httpException) {
        HttpStatus status = httpException.getStatus();
        return status.equals(HttpStatus.UNAUTHORIZED) || status.equals(HttpStatus.NOT_FOUND);
    }
}
