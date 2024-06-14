package com.epam.aidial.core.cache;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ConfigSupport;
import org.redisson.config.CredentialsResolver;

import java.util.Objects;

@UtilityClass
public class CacheClientFactory {
    @SneakyThrows
    public RedissonClient create(JsonObject conf) {
        if (conf.isEmpty()) {
            throw new IllegalArgumentException("Redis configuration not found");
        }

        JsonObject providerSettings = conf.getJsonObject("provider");
        CacheProvider provider = null;
        if (providerSettings != null) {
            provider = CacheProvider.from(providerSettings.getString("name"));
        }
        CredentialsResolver credentialsResolver = null;
        if (provider == CacheProvider.AWS_ELASTI_CACHE) {
            credentialsResolver = createElastiCacheCredResolver(providerSettings);
        }

        ConfigSupport support = new RedisConfigSupport();
        Config config = support.fromJSON(conf.toString(), Config.class);
        if (credentialsResolver != null) {
            if (config.isClusterConfig()) {
                config.useClusterServers().setCredentialsResolver(credentialsResolver);
            } else {
                config.useSingleServer().setCredentialsResolver(credentialsResolver);
            }
        }
        return Redisson.create(config);
    }

    private CredentialsResolver createElastiCacheCredResolver(JsonObject providerSettings) {
        String userId = Objects.requireNonNull(providerSettings.getString("userId"), "AIM user must be provided");
        String region = Objects.requireNonNull(providerSettings.getString("region"), "AWS region ID must be provided");
        String clusterName = Objects.requireNonNull(providerSettings.getString("clusterName"), "Redis cluster name must be provided");
        boolean serverless = Objects.requireNonNull(providerSettings.getBoolean("serverless"), "Serverless flag must be provided");
        IamAuthTokenRequest iamAuthTokenRequest = new IamAuthTokenRequest(userId, clusterName, region, serverless);
        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        return new AwsCredentialsResolver(userId, iamAuthTokenRequest, awsCredentialsProvider);
    }
}
