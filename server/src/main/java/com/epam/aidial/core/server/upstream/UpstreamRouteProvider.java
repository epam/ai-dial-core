package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import io.vertx.core.Vertx;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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

    public UpstreamRouteProvider(Vertx vertx, Supplier<Random> generatorFactory) {
        this.generatorFactory = generatorFactory;
        vertx.setPeriodic(0, TimeUnit.MINUTES.toMillis(1), event -> evictExpiredBalancers());
    }

    public UpstreamRoute get(Deployment deployment) {
        String key = getKey(deployment);
        List<Upstream> upstreams = getUpstreams(deployment);
        return get(key, upstreams, deployment.getMaxRetryAttempts());
    }

    public UpstreamRoute get(Route route) {
        String key = getKey(route);
        return get(key, route.getUpstreams(), route.getMaxRetryAttempts());
    }

    private UpstreamRoute get(String key, List<Upstream> upstreams, int maxRetryAttempts) {
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
        return new UpstreamRoute(wrapper.balancer, result);
    }

    private List<Upstream> getUpstreams(Deployment deployment) {
        if (deployment instanceof Model model && !model.getUpstreams().isEmpty()) {
            return model.getUpstreams();
        }

        Upstream upstream = new Upstream();
        upstream.setEndpoint(deployment.getEndpoint());
        upstream.setKey("whatever");
        return List.of(upstream);
    }

    private String getKey(Route route) {
        Objects.requireNonNull(route);
        return "route:" + route.getName();
    }

    private String getKey(Deployment deployment) {
        Objects.requireNonNull(deployment);
        String prefix;
        if (deployment instanceof Model) {
            prefix = "model";
        } else if (deployment instanceof Application) {
            prefix = "application";
        } else if (deployment instanceof Assistant) {
            prefix = "assistant";
        } else {
            throw new IllegalArgumentException("Unsupported deployment type: " + deployment.getClass().getName());
        }
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
