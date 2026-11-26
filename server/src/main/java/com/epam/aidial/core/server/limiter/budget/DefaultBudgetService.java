package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.DefaultBudget;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import javax.annotation.Nullable;

/**
 * The user's default budget: a private record in their bucket that names one budget they hold. Every method blocks,
 * so it is for a caller already on the task executor.
 */
@RequiredArgsConstructor
public class DefaultBudgetService {

    private static final String RECORD_NAME = "default";

    private final ResourceService resourceService;

    /**
     * @return the caller's default budget while they hold it; {@code null} when none is set or the stored one is
     *         withdrawn, expired or no longer matches them
     */
    @Nullable
    public BudgetInstance findDefault(ProxyContext context) {
        DefaultBudget stored = ProxyUtil.convertToObject(resourceService.getResource(buildDescriptor(context)), DefaultBudget.class);
        return stored == null || stored.getBudget() == null ? null : findHeld(context, stored.getBudget());
    }

    /**
     * Stores the budget as the caller's default when they hold it now.
     *
     * @return whether it was stored
     */
    public boolean setDefault(ProxyContext context, String budgetId) {
        if (findHeld(context, budgetId) == null) {
            return false;
        }
        resourceService.putResource(buildDescriptor(context), ProxyUtil.convertToString(new DefaultBudget(budgetId)), EtagHeader.ANY);
        return true;
    }

    public void clearDefault(ProxyContext context) {
        resourceService.deleteResource(buildDescriptor(context), EtagHeader.ANY);
    }

    /**
     * The budget among those {@code GET /v1/user/limits} lists for the caller, so a caller can name only what they
     * can see. On the wall clock, as the budget checks are.
     */
    @Nullable
    private BudgetInstance findHeld(ProxyContext context, String budgetId) {
        return EffectiveBudgets.resolveAll(context.getConfig().getCostObjectIndex(), context.getExtractedClaims(), Instant.now())
                .stream()
                .filter(instance -> instance.id().equals(budgetId))
                .findFirst()
                .orElse(null);
    }

    private static ResourceDescriptor buildDescriptor(ProxyContext context) {
        String bucketLocation = BucketBuilder.buildInitiatorBucket(context);
        return ResourceDescriptorFactory.fromEntityPath(ResourceTypes.USER_BUDGET_DEFAULT, bucketLocation, bucketLocation, RECORD_NAME);
    }
}
