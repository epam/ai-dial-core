package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.data.cache.CacheBreakpointContext;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * This class caches load balancers for deployments and routes.
 * If upstreams configuration for any deployment changed - load balancer state will be invalidated.
 */
@Slf4j
public class UpstreamRouteProvider {

    /**
     * Maximum idle period while balancers will stay in the local cache.
     */
    private static final long IDLE_PERIOD_IN_MS = TimeUnit.HOURS.toMillis(1);


    /**
     * Cached load balancers
     */
    private final ConcurrentHashMap<String, BalancerWrapper> balancers = new ConcurrentHashMap<>();

    private final Supplier<Random> generatorFactory;

    private final UpstreamCacheService upstreamCacheService;

    private final AsyncTaskExecutor taskExecutor;

    public UpstreamRouteProvider(Vertx vertx, AsyncTaskExecutor taskExecutor, Supplier<Random> generatorFactory, UpstreamCacheService upstreamCacheService) {
        this.generatorFactory = generatorFactory;
        this.upstreamCacheService = upstreamCacheService;
        vertx.setPeriodic(0, TimeUnit.MINUTES.toMillis(1), event -> evictExpiredBalancers());
        this.taskExecutor = taskExecutor;
    }

    public UpstreamRoute get(Deployment deployment, CacheBreakpointContext breakpointContext) {
        return get(deployment, breakpointContext, Deployment::getEndpoint, null);
    }

    public UpstreamRoute get(Deployment deployment, CacheBreakpointContext breakpointContext, String upstreamId) {
        return get(deployment, breakpointContext, Deployment::getEndpoint, upstreamId);
    }

    public UpstreamRoute get(Deployment deployment, CacheBreakpointContext breakpointContext,
                             Function<Deployment, String> endpointSupplier) {
        return get(deployment, breakpointContext, endpointSupplier, null);
    }

    public UpstreamRoute get(Deployment deployment, CacheBreakpointContext breakpointContext,
                             Function<Deployment, String> endpointSupplier, String upstreamId) {
        String key = getKey(deployment);
        List<Upstream> upstreams = getUpstreams(deployment, endpointSupplier);
        if (upstreamId != null && !upstreamId.isBlank()) {
            Upstream selected = null;
            for (Upstream upstream : upstreams) {
                String id = Objects.requireNonNullElse(upstream.getId(), upstream.getEndpoint());
                if (upstreamId.equals(id)) {
                    selected = upstream;
                    break;
                }
            }
            if (selected == null) {
                throw new HttpException(HttpStatus.BAD_REQUEST, "Unknown upstream id " + upstreamId);
            }
            upstreams = List.of(selected);
            key = key + ":upstream:" + upstreamId;
        }
        UpstreamCacheContext context = null;
        if (deployment instanceof Model model && breakpointContext != null) {
            CachedUpstreamEntry entry = upstreamCacheService.getCacheEntry(breakpointContext, model);
            context = new UpstreamCacheContext();
            context.setPolicy(breakpointContext.policy());
            context.setEntry(entry);
            context.setPrefixToHash(breakpointContext.prefixToHash());
        }
        return get(key, upstreams, deployment.getMaxRetryAttempts(), context);
    }

    public UpstreamRoute get(Route route) {
        String key = getKey(route);
        return get(key, route.getUpstreams(), route.getMaxRetryAttempts(), null);
    }

    private UpstreamRoute get(String key, List<Upstream> upstreams, int maxRetryAttempts, UpstreamCacheContext context) {
        BalancerWrapper wrapper = balancers.compute(key, (k, cur) -> {
            BalancerWrapper result;
            if (cur != null && isUpstreamsTheSame(cur.upstreams, upstreams)
                    && maxRetryAttempts == cur.maxRetryAttempts) {
                result = cur;
            } else {
                TieredBalancer balancer = new TieredBalancer(key, upstreams, generatorFactory.get());
                result = new BalancerWrapper(balancer, maxRetryAttempts, upstreams);
            }
            result.lastAccessTime = System.currentTimeMillis();
            return result;
        });
        int result = Math.min(maxRetryAttempts, upstreams.size());
        if (result <= 0) {
            throw new IllegalArgumentException("max retry attempts must be positive integer");
        }
        if (context != null && context.getEntry() != null) {
            String cachedId = context.getEntry().id();
            String cachedEndpoint = context.getEntry().endpoint();
            Upstream originalUpstream = null;
            if (cachedId != null) {
                for (Upstream upstream : upstreams) {
                    if (cachedId.equals(upstream.getId())) {
                        originalUpstream = upstream;
                        break;
                    }
                }
            }
            // fallback to upstream endpoint if id is not set
            if (originalUpstream == null && cachedEndpoint != null) {
                for (Upstream upstream : upstreams) {
                    if (cachedEndpoint.equals(upstream.getEndpoint())) {
                        originalUpstream = upstream;
                        break;
                    }
                }
            }
            if (originalUpstream != null) {
                context.setOriginalUpstream(originalUpstream);
            } else {
                log.warn("cached upstream doesn't exist any longer in config: id={}, endpoint={}", cachedId, cachedEndpoint);
            }
        }
        return new UpstreamRoute(taskExecutor, upstreamCacheService, wrapper.balancer, result, context);
    }

    private List<Upstream> getUpstreams(Deployment deployment, Function<Deployment, String> endpointSupplier) {
        if (deployment instanceof Model model && !model.getUpstreams().isEmpty()) {
            return model.getUpstreams();
        }

        Upstream upstream = new Upstream();
        String endpoint = endpointSupplier.apply(deployment);
        upstream.setEndpoint(endpoint);
        upstream.setResponsesEndpoint(deployment.getResponsesEndpoint());
        upstream.setKey("whatever");
        upstream.setId(endpoint);
        return List.of(upstream);
    }

    private String getKey(Route route) {
        Objects.requireNonNull(route);
        return "route:" + route.getName();
    }

    private String getKey(Deployment deployment) {
        Objects.requireNonNull(deployment);
        String prefix = switch (deployment) {
            case Model ignored -> "model";
            case Application ignored -> "application";
            case ToolSet ignored -> "toolset";
            default ->
                    throw new IllegalArgumentException("Unsupported deployment type: " + deployment.getClass().getName());
        };
        return prefix + ":" + deployment.getName();
    }

    private void evictExpiredBalancers() {
        long currentTime = System.currentTimeMillis();
        for (String key : balancers.keySet()) {
            balancers.compute(key, (k, wrapper) -> {
                if (wrapper != null && currentTime - wrapper.lastAccessTime > IDLE_PERIOD_IN_MS) {
                    return null;
                }
                return wrapper;
            });
        }
    }

    private static boolean isUpstreamsTheSame(List<Upstream> a, List<Upstream> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }

    private static class BalancerWrapper {
        final TieredBalancer balancer;
        long lastAccessTime;

        /**
         * Note. The value is taken from {@link Deployment#getMaxRetryAttempts()} or {@link Route#getMaxRetryAttempts()}
         */
        final int maxRetryAttempts;

        final List<Upstream> upstreams;

        public BalancerWrapper(TieredBalancer balancer, int maxRetryAttempts, List<Upstream> upstreams) {
            this.balancer = balancer;
            this.maxRetryAttempts = maxRetryAttempts;
            this.upstreams = upstreams;
        }
    }
}
