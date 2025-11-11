package com.epam.aidial.core.storage.cache;

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
        CredentialsResolver credentialsResolver = null;
        if (providerSettings != null) {
            CacheProvider provider = CacheProvider.from(providerSettings.get("name").asText());
            credentialsResolver = switch (provider) {
                case AWS_ELASTI_CACHE -> createElastiCacheCredResolver(providerSettings);
                case GCP_MEMORY_STORE -> createGcpCredResolver(providerSettings);
                case AZURE_REDIS_CACHE -> createAzureCredResolver();
                case LOCAL_CACHE -> createLocalCredResolver(providerSettings);
            };
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

    private static CredentialsResolver createElastiCacheCredResolver(JsonNode providerSettings) {
        String userId = Objects.requireNonNull(providerSettings.get("userId"), "AIM user must be provided").asText();
        String region = Objects.requireNonNull(providerSettings.get("region"), "AWS region ID must be provided").asText();
        String clusterName = Objects.requireNonNull(providerSettings.get("clusterName"), "Redis cluster name must be provided").asText();
        boolean serverless = Objects.requireNonNull(providerSettings.get("serverless"), "Serverless flag must be provided").asBoolean();
        IamAuthTokenRequest iamAuthTokenRequest = new IamAuthTokenRequest(userId, clusterName, region, serverless);
        AWSCredentialsProvider awsCredentialsProvider = new DefaultAWSCredentialsProviderChain();
        return new AwsCredentialsResolver(userId, iamAuthTokenRequest, awsCredentialsProvider);
    }

    private static CredentialsResolver createGcpCredResolver(JsonNode providerSettings) {
        String accountName = Objects.requireNonNull(providerSettings.get("accountName"), "AIM account name must be provided").asText();
        return new GcpCredentialsResolver(accountName);
    }

    private static CredentialsResolver createAzureCredResolver() {
        return new AzureCredentialsResolver();
    }

    private static CredentialsResolver createLocalCredResolver(JsonNode providerSettings) {
        String userId = Objects.requireNonNull(providerSettings.get("userId"), "User must be provided").asText();
        String password = Objects.requireNonNull(providerSettings.get("password"), "Password must be provided").asText();
        return new LocalRedisCredentialsResolver(userId.isEmpty() ? null : userId, password);
    }
}
