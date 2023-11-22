package com.epam.aidial.core.storage;

import java.util.List;
import java.util.Random;

import org.apache.commons.math3.distribution.ZipfDistribution;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;


@State(Scope.Benchmark)
@Threads(2)
@Warmup(time = 3, iterations = 5)
@Measurement(time = 5, iterations = 5)
public class QueryBenchmark {
    private final double[] USER_TYPES = new double[] {0.7, 0.05, 0.25};
    private final int USER_COUNT = 50_000;
    private Random random;
    int[] userIndexOffset;
    ZipfDistribution[] zipfDistribution;

    ConversationStorage conversationStorage;

    @Setup(Level.Trial)
    public void initDistribution() {
        random = new Random();
        userIndexOffset = new int[USER_TYPES.length];
        zipfDistribution = new ZipfDistribution[USER_TYPES.length];

        int offset = 0;
        for (int i = 0; i < USER_TYPES.length; i++) {
            double coef = USER_TYPES[i];
            int count = (int) (USER_COUNT * coef);
            userIndexOffset[i] = offset;
            zipfDistribution[i] = new ZipfDistribution(count, 0.5573040992021561);
            offset += count;
        }
        ContextBuilder builder = ContextBuilder.newBuilder("azureblob");
//        builder.credentials("stagingdialtest",
//                "");
        builder.credentials("dialtestlarge",
                "");
        var storeContext = builder.buildView(BlobStoreContext.class);
        var blobStore = storeContext.getBlobStore();

        Config config = new Config();
        config.useClusterServers()
//                .setMasterConnectionPoolSize(10)
//                .setSlaveConnectionPoolSize(10)
                .setNodeAddresses(List.of(
                    "redis://localhost:6380",
                    "redis://localhost:6381",
                    "redis://localhost:6382",
                    "redis://localhost:6383",
                    "redis://localhost:6384",
                    "redis://localhost:6385"));
//        config.useSingleServer()
//                //.setConnectionPoolSize(10)
//                .setAddress("redis://localhost:6379");
        RedissonClient client = Redisson.create(config);

        conversationStorage = new ConversationStorage(blobStore, client, "rail");
    }

    @TearDown(Level.Trial)
    public void teardown() {
        conversationStorage.redisson.shutdown();
    }

    public String genPath(int type, boolean isRecent, boolean isDir) {
        int userIndex = zipfDistribution[type].sample() + userIndexOffset[type];
        String userDir = String.format("user/%d/conversations", userIndex);

        if (type == 0) {
            return isDir ? userDir : String.format("%s/c-%d.txt", userDir, random.nextInt(isRecent ? 10 : 4000));
        } else if (type == 1) {
            if (isDir) {
                if (isRecent) {
                    return String.format("%s/dir-0/dir-%d/", userDir, random.nextInt(10));
                } else {
                    return String.format("%s/dir-%d/dir-%d/", userDir, random.nextInt(4), random.nextInt(10));
                }
            } else {
                if (isRecent) {
                    return String.format("%s/dir-0/dir-0/c-%d", userDir, random.nextInt(10));
                } else {
                    return String.format("%s/dir-%d/dir-%d/c-%d", userDir, random.nextInt(4), random.nextInt(10),
                            random.nextInt(100));
                }
            }
        } else {
            return isDir ? userDir : String.format("%s/c-%d.txt", userDir, random.nextInt(isRecent ? 10 : 32));
        }
    }

//    public static void main(String[] args) {
//        QueryBenchmark q = new QueryBenchmark();
//        q.initDistribution();
//
//        for (int i = 0; i < 100; ++i) {
//            System.out.println(q.genPath(0, true, true));
//        }
//    }

    @Benchmark
    public List<String> type0ListRecentDir() {
        return conversationStorage.listConversations(genPath(0, true, true));
    }

    @Benchmark
    public List<String> type1ListRecentDir() {
        return conversationStorage.listConversations(genPath(1, true, true));
    }

    @Benchmark
    public List<String> type2ListRecentDir() {
        return conversationStorage.listConversations(genPath(2, true, true));
    }

    @Benchmark
    public List<String> type0ListRandomDir() {
        return conversationStorage.listConversations(genPath(0, false, true));
    }

    @Benchmark
    public List<String> type1ListRandomDir() {
        return conversationStorage.listConversations(genPath(1, false, true));
    }

    @Benchmark
    public List<String> type2ListRandomDir() {
        return conversationStorage.listConversations(genPath(2, false, true));
    }

    @Benchmark
    public List<String> type0ListDirRealistic() {
        return (random.nextInt(100) < 5)
                ? conversationStorage.listConversations(genPath(0, false, true))
                : conversationStorage.listConversations(genPath(0, true, true));
    }

    @Benchmark
    public List<String> type1ListDirRealistic() {
        return (random.nextInt(100) < 5)
                ? conversationStorage.listConversations(genPath(1, false, true))
                : conversationStorage.listConversations(genPath(1, true, true));
    }

    @Benchmark
    public List<String> type2ListDirRealistic() {
        return (random.nextInt(100) < 5)
                ? conversationStorage.listConversations(genPath(2, false, true))
                : conversationStorage.listConversations(genPath(2, true, true));
    }

    @Benchmark
    public String type0AccessRecentFile() {
        return conversationStorage.getConversation(genPath(0, true, false));
    }

    @Benchmark
    public String type1AccessRecentFile() {
        return conversationStorage.getConversation(genPath(1, true, false));
    }

    @Benchmark
    public String type2AccessRecentFile() {
        return conversationStorage.getConversation(genPath(2, true, false));
    }

    @Benchmark
    public String type0AccessRandomFile() {
        return conversationStorage.getConversation(genPath(0, false, false));
    }

    @Benchmark
    public String type1AccessRandomFile() {
        return conversationStorage.getConversation(genPath(1, false, false));
    }

    @Benchmark
    public String type2AccessRandomFile() {
        return conversationStorage.getConversation(genPath(2, false, false));
    }

    @Benchmark
    public String type0AccessFileRealistic() {
        return (random.nextInt(100) < 5)
                ? conversationStorage.getConversation(genPath(0, false, false))
                : conversationStorage.getConversation(genPath(0, true, false));
    }

    @Benchmark
    public String type1AccessFileRealistic() {
        return (random.nextInt(100) < 5)
                ? conversationStorage.getConversation(genPath(1, false, false))
                : conversationStorage.getConversation(genPath(1, true, false));
    }

    @Benchmark
    public String type2AccessFileRealistic() {
        return (random.nextInt(100) < 5)
                ? conversationStorage.getConversation(genPath(2, false, false))
                : conversationStorage.getConversation(genPath(2, true, false));
    }
}
