package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.CalendarWindowCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Spend per budget instance, user and model, kept as one row at
 * {@code platform/limits/<costObject>/budgets/<instance>/<user>/<model>/costs}. The rows are not part of the
 * config and have no route.
 *
 * <p>A user id or model that cannot form a valid path (it holds a slash, a control character, a brace or a
 * double quote, or the whole path exceeds the size limit) is rejected with {@link IllegalArgumentException}
 * before any storage call.
 */
@RequiredArgsConstructor
public class BudgetCounterStore {

    private final AsyncTaskExecutor taskExecutor;
    private final ResourceService resourceService;

    /**
     * Spent so far by a user in all models in the current windows, and when the windows reset.
     * A row of an earlier window counts as zero.
     */
    public Future<BudgetUsage> load(String costObject, BudgetInstance instance, String userId, long timestamp, RateLimitSchedule schedule) {
        ResourceDescriptor userFolder = describe(costObject, instance, userId, null);
        return taskExecutor.submit(() -> {
            BigDecimal day = BigDecimal.ZERO;
            BigDecimal month = BigDecimal.ZERO;
            for (var row : resourceService.listResources(userFolder, page -> { })) {
                BudgetCounter counter = ProxyUtil.convertToObject(row.getValue(), BudgetCounter.class);
                day = day.add(counter.getDay().reconcile(timestamp, CalendarPeriod.DAY, schedule));
                month = month.add(counter.getMonth().reconcile(timestamp, CalendarPeriod.MONTH, schedule));
            }
            return new BudgetUsage(day, month,
                    CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, timestamp, schedule),
                    CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.MONTH, timestamp, schedule));
        });
    }

    /**
     * Adds a cost in full to the current windows, even when it takes the budget over its limit.
     */
    public Future<Void> charge(String costObject, BudgetInstance instance, String userId, String model,
                               BigDecimal cost, long timestamp, RateLimitSchedule schedule) {
        ResourceDescriptor descriptor = describe(costObject, instance, userId, model);
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            return Future.succeededFuture();
        }
        return taskExecutor.submit(() -> {
            resourceService.computeResource(descriptor, json -> {
                BudgetCounter counter = json == null ? new BudgetCounter() : ProxyUtil.convertToObject(json, BudgetCounter.class);
                counter.getDay().add(timestamp, CalendarPeriod.DAY, schedule, cost);
                counter.getMonth().add(timestamp, CalendarPeriod.MONTH, schedule, cost);
                return ProxyUtil.convertToString(counter);
            });
            return null;
        });
    }

    /**
     * @param model {@code null} for the folder holding every model of the user
     */
    private static ResourceDescriptor describe(String costObject, BudgetInstance instance, String userId, String model) {
        // the instance id starts with the cost object, which is already the first element of the path
        String instanceKey = instance.id().substring(instance.id().indexOf('/') + 1);
        Stream<String> elements = Stream.concat(
                Stream.of(costObject, "budgets"),
                Stream.concat(Stream.of(instanceKey.split("/")), Stream.of(userId)));
        if (model != null) {
            elements = Stream.concat(elements, Stream.of(model, "costs"));
        }
        // every element is encoded exactly once, the factory decodes it back to the stored key
        String path = elements.map(UrlUtil::encodePathSegment).collect(Collectors.joining("/"));
        return ResourceDescriptorFactory.fromEncoded(ResourceTypes.LIMIT, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, model == null ? path + "/" : path);
    }
}
