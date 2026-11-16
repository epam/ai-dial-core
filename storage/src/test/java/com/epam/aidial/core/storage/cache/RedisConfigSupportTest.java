package com.epam.aidial.core.storage.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import org.junit.jupiter.api.Test;
import org.redisson.client.NettyHook;
import org.redisson.config.Config;
import org.redisson.config.SslVerificationMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisConfigSupportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Config parse(String json) throws Exception {
        return RedisConfigSupport.parse(MAPPER.readTree(json));
    }

    @Test
    void testSingleServer() throws Exception {
        Config config = parse("""
                {"singleServerConfig": {"address": "redis://localhost:16370"}}
                """);

        assertTrue(config.isSingleConfig());
        assertFalse(config.isClusterConfig());
        assertEquals("redis://localhost:16370", config.useSingleServer().getAddress());
    }

    @Test
    void testClusterServers() throws Exception {
        Config config = parse("""
                {"clusterServersConfig": {"nodeAddresses": ["rediss://node1:6379", "rediss://node2:6379"]}}
                """);

        assertTrue(config.isClusterConfig());
        assertEquals(List.of("rediss://node1:6379", "rediss://node2:6379"), config.useClusterServers().getNodeAddresses());
    }

    @Test
    void testConnectionTuningSettings() throws Exception {
        Config config = parse("""
                {"singleServerConfig": {"address": "redis://localhost:6379", "retryAttempts": 7, "retryInterval": 1500,
                 "timeout": 4000, "pingConnectionInterval": 5000, "keepAlive": true, "connectionPoolSize": 32}}
                """);

        var single = config.useSingleServer();
        assertEquals(7, single.getRetryAttempts());
        assertEquals(1500, single.getRetryInterval());
        assertEquals(4000, single.getTimeout());
        assertEquals(5000, single.getPingConnectionInterval());
        assertTrue(single.isKeepAlive());
        assertEquals(32, single.getConnectionPoolSize());
    }

    @Test
    void testClusterRetryInterval() throws Exception {
        Config config = parse("""
                {"clusterServersConfig": {"nodeAddresses": ["redis://node1:6379"], "retryInterval": 2500, "retryAttempts": 3}}
                """);

        assertEquals(2500, config.useClusterServers().getRetryInterval());
        assertEquals(3, config.useClusterServers().getRetryAttempts());
    }

    @Test
    void testTopLevelSettings() throws Exception {
        Config config = parse("""
                {"nettyThreads": 12, "threads": 6, "sslVerificationMode": "NONE", "username": "user", "password": "secret",
                 "singleServerConfig": {"address": "rediss://localhost:6379"}}
                """);

        assertEquals(12, config.getNettyThreads());
        assertEquals(6, config.getThreads());
        assertEquals(SslVerificationMode.NONE, config.getSslVerificationMode());
        assertEquals("user", config.getUsername());
        assertEquals("secret", config.getPassword());
    }

    @Test
    void testClassFormObjectSetting() throws Exception {
        Config config = parse("""
                {"singleServerConfig": {"address": "redis://localhost:6379"},
                 "nettyHook": {"class": "com.epam.aidial.core.storage.cache.RedisConfigSupportTest$TestNettyHook"}}
                """);

        assertTrue(config.getNettyHook() instanceof TestNettyHook);
    }

    public static class TestNettyHook implements NettyHook {
        @Override
        public void afterBoostrapInitialization(Bootstrap bootstrap) {
            // no-op
        }

        @Override
        public void afterChannelInitialization(Channel channel) {
            // no-op
        }
    }

    @Test
    void testSpecialCharactersInValues() throws Exception {
        Config config = parse("""
                {"singleServerConfig": {"address": "redis://localhost:6379",
                  "password": "p: #a, {b} [c] \\"q\\" 'x' !y &z *w\\nline", "clientName": "true"}}
                """);

        assertEquals("p: #a, {b} [c] \"q\" 'x' !y &z *w\nline", config.useSingleServer().getPassword());
        assertEquals("true", config.useSingleServer().getClientName());
    }

    @Test
    void testProviderBlockIsNotPassedToRedisson() throws Exception {
        Config config = parse("""
                {"singleServerConfig": {"address": "rediss://localhost:6379"},
                 "provider": {"name": "aws-elasti-cache", "userId": "u", "region": "r", "clusterName": "c", "serverless": true}}
                """);

        assertEquals("rediss://localhost:6379", config.useSingleServer().getAddress());
    }

    @Test
    void testInputIsNotModified() throws Exception {
        var node = MAPPER.readTree("""
                {"singleServerConfig": {"address": "redis://localhost:6379"}, "provider": {"name": "gcp-memory-store"}}
                """);

        RedisConfigSupport.parse(node);

        assertTrue(node.has("provider"));
    }

    @Test
    void testUnknownSettingFailsFast() {
        var e = assertThrows(IllegalArgumentException.class, () -> parse("""
                {"singleServerConfig": {"address": "redis://localhost:6379", "noSuchSetting": 1}}
                """));

        assertTrue(e.getMessage().startsWith("Invalid redis configuration"));
    }
}
