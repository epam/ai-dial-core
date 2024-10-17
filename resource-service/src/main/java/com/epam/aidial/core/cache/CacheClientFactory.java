package com.epam.aidial.core.cache;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.fasterxml.jackson.databind.JsonNode;
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
    public RedissonClient create(JsonNode conf) {
        if (conf.isEmpty()) {
            throw new IllegalArgumentException("Redis configuration not found");
        }

        JsonNode providerSettings = conf.get("provider");
        CacheProvider provider = null;
        if (providerSettings != null) {
            provider = CacheProvider.from(providerSettings.get("name").asText());
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

    private CredentialsResolver createElastiCacheCredResolver(JsonNode providerSettings) {
        String userId = Objects.requireNonNull(providerSettings.get("userId"), "AIM user must be provided").asText();
        String region = Objects.requireNonNull(providerSettings.get("region"), "AWS region ID must be provided").asText();
        String clusterName = Objects.requireNonNull(providerSettings.get("clusterName"), "Redis cluster name must be provided").asText();
        boolean serverless = Objects.requireNonNull(providerSettings.get("serverless"), "Serverless flag must be provided").asBoolean();
        IamAuthTokenRequest iamAuthTokenRequest = new IamAuthTokenRequest(userId, clusterName, region, serverless);
        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        return new AwsCredentialsResolver(userId, iamAuthTokenRequest, awsCredentialsProvider);
    }
}
