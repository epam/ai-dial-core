package com.epam.aidial.core.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.epam.aidial.core.storage.BlobStorage.buildListContainerOptions;

import io.micrometer.core.instrument.util.IOUtils;
import org.apache.commons.math3.distribution.ZipfDistribution;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
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
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;


@State(Scope.Benchmark)
@Threads(4)
@Warmup(time = 3, iterations = 5)
@Measurement(time = 5, iterations = 5)
public class QueryBenchmark {
    private final double[] USER_TYPES = new double[] {0.7, 0.05, 0.25};
    private final int USER_COUNT = 50_000;
    private final int BIG_FILE_COUNT = 4_500;
    private Random random;
    int[] userIndexOffset;
    ZipfDistribution[] zipfDistribution;
    private byte[] payload8k;
    private byte[] payload1024k;
    private String payload1024kStr;

    private int[][] test;


    ConversationStorage conversationStorage;

    AtomicInteger threadCounter = new AtomicInteger(0);
    ThreadLocal<Integer> threadNumber = ThreadLocal.withInitial(() -> threadCounter.incrementAndGet());
    ThreadLocal<Integer> rowIndex = ThreadLocal.withInitial(() -> 0);
    AtomicInteger commonIndex = new AtomicInteger(0);

    BlobStore blobStore;

    //@Setup(Level.Trial)
    public void initDistribution() throws IOException {
//        random = new Random();
//        userIndexOffset = new int[USER_TYPES.length];
//        zipfDistribution = new ZipfDistribution[USER_TYPES.length];
//        payload8k = IOUtils.toString(Objects.requireNonNull(
//                ConversationStorage.class.getResourceAsStream("/payload_8k.txt")),
//                StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
//        payload1024k = IOUtils.toString(Objects.requireNonNull(
//                ConversationStorage.class.getResourceAsStream("/payload_1024k.txt")),
//                StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
//
//        var users = new ArrayList<Integer>();
//        var chats = new ArrayList<Integer>();
//        try (var testStream = ConversationStorage.class.getResourceAsStream("/test.csv")) {
//            try (var reader = new BufferedReader(new InputStreamReader(testStream))) {
//                String s = reader.readLine();
//                while (s != null) {
//                    String[] split = s.split(", ");
//                    users.add(Integer.parseInt(split[1]));
//                    chats.add(Integer.parseInt(split[2]));
//                    s = reader.readLine();
//                }
//            }
//        }
//        test = new int[2][];
//        test[0] = users.stream().mapToInt(Integer::intValue).toArray();
//        test[1] = chats.stream().mapToInt(Integer::intValue).toArray();
//
//        IOUtils.toString(Objects.requireNonNull(
//                ConversationStorage.class.getResourceAsStream("/test.csv")),
//                StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
//
//        int offset = 0;
//        for (int i = 0; i < USER_TYPES.length; i++) {
//            double coef = USER_TYPES[i];
//            int count = (int) (USER_COUNT * coef);
//            userIndexOffset[i] = offset;
//            zipfDistribution[i] = new ZipfDistribution(count, 0.5573040992021561);
//            offset += count;
//        }
//        ContextBuilder builder = ContextBuilder.newBuilder("azureblob");
//        builder.credentials(ConversationStorage.CONTAINER, ConversationStorage.ACCESS_KEY);
//        var storeContext = builder.buildView(BlobStoreContext.class);
//        var blobStore = storeContext.getBlobStore();
//
//        Config config = new Config();
//        config.useClusterServers()
//                .setNodeAddresses(List.of(
//                    "redis://localhost:6380",
//                    "redis://localhost:6381",
//                    "redis://localhost:6382",
//                    "redis://localhost:6383",
//                    "redis://localhost:6384",
//                    "redis://localhost:6385"));
////        config.useSingleServer()
////                .setAddress("redis://localhost:6379");
//        RedissonClient client = Redisson.create(config);
//
//        conversationStorage = new ConversationStorage(blobStore, client, "rail");
//
//        payload1024kStr = new String(payload1024k);
//        for (int i = 0; i < BIG_FILE_COUNT; ++i) {
//            var bucket = conversationStorage.redisson.getBucket("" + i);
//            System.out.println("Writing " + i);
//            bucket.set(payload1024kStr);
//        }

        initAWS();
    }

    List<String> s3Files = new ArrayList<>();

    @Setup(Level.Trial)
    public void initAWS() {
        ContextBuilder builder = ContextBuilder.newBuilder("aws-s3")
                        .credentials("", "");

        var storeContext = builder.buildView(BlobStoreContext.class);
        this.blobStore = storeContext.getBlobStore();
        //blobStore.getBlob("staging-dial-test", "");

        ListContainerOptions options = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(""));
        var topLevel = blobStore.list("staging-dial-test", options).stream().map(StorageMetadata::getName).toList();

        for (var folder : topLevel) {
            ListContainerOptions options2 = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(folder));
            var secondLevel = blobStore.list("staging-dial-test", options2).stream().map(StorageMetadata::getName).toList();
            for (var file : secondLevel) {
                s3Files.add(file);
            }
        }

        random = new Random(1);
    }

    @Benchmark
    public void randomAccessS3File() throws IOException {
        var path = s3Files.get(random.nextInt(s3Files.size()));
        var blob = blobStore.getBlob("staging-dial-test", path);

        var data = blob.getPayload().openStream().readAllBytes();

        if (data.length != 1_048_576) {
            throw new RuntimeException("Not equal 1071153");
        }
    }


//    @Benchmark
//    public List<String> type0ListRecentDir() {
//        return conversationStorage.listConversations(getTestPath(0, true, true));
//    }


    @TearDown(Level.Trial)
    public void teardown() {
        conversationStorage.redisson.shutdown();
    }

    public String getTestPath(int type, /*ignored*/ boolean isRecent, boolean isDir) {
        int index = rowIndex.get();
        rowIndex.set(index + 1);

        int threadId = threadNumber.get();
        int totalUserOfType = (int)(USER_COUNT * USER_TYPES[type]);
        int userIndex = test[0][index] + totalUserOfType / threadCounter.get() * (threadId - 1);
        userIndex %= totalUserOfType;
        userIndex += userIndexOffset[type];
        int chatIndex = test[1][index];
        String userDir = String.format("user/%d/conversations", userIndex);

        if (type == 0) {
            return isDir ? userDir : String.format("%s/c-%d.txt", userDir, chatIndex);
        } else if (type == 1) {
            if (isDir) {
                return String.format("%s/dir-%d/dir-%d/", userDir, chatIndex / 10, chatIndex % 10);
            } else {
                return String.format("%s/dir-%d/dir-%d/c-%d", userDir, chatIndex / 1000, (chatIndex / 10) % 10, chatIndex % 10);
            }
        } else {
            return isDir ? userDir : String.format("%s/c-%d.txt", userDir, chatIndex % 32);
        }
    }

    public String genZipfPath(int type, boolean isRecent, boolean isDir) {
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

    public String getNextPath(/*ignored*/ int type, /*ignored*/ boolean isRecent, boolean isDir) {
        int index = commonIndex.incrementAndGet();

        int userIndex = index / 4_000;
        int chatIndex =  index % 4_000;
        String userDir = String.format("user/%d/conversations", userIndex);

        return isDir ? userDir : String.format("%s/c-%d.txt", userDir, chatIndex);
    }

    public String getRandomPath(/*ignored*/ int type, /*ignored*/ boolean isRecent, boolean isDir) {
        int index = random.nextInt(4_000 * 35_000);

        int userIndex = index / 4_000;
        int chatIndex =  index % 4_000;
        String userDir = String.format("user/%d/conversations", userIndex);

        return isDir ? userDir : String.format("%s/c-%d.txt", userDir, chatIndex);
    }

    public static void main(String[] args) throws IOException {
        var q = new QueryBenchmark();
        q.initAWS();

//        QueryBenchmark q = new QueryBenchmark();
//        q.initDistribution();
//
////        var file = q.type2AccessRecentFile();
//
////        for (int i = 0; i < 100; ++i) {
////            if (q.random.nextInt(100) < 5) {
////                System.out.println(q.genPath(0, false, true));
////            } else {
////                System.out.println(q.genPath(0, true, true));
////            }
////        }
//        var counts = new TreeMap<Integer, Integer>();
//        for (int i = 0; i < 10000; ++i) {
//            int x = q.zipfDistribution[0].sample();
//            counts.computeIfAbsent(x, y -> 0);
//            counts.put(x, counts.get(x) + 1);
//        }
//        for (var x : counts.entrySet()) {
//            System.out.println(x.getValue());
//            //System.out.println(x.getKey() + " " +  x.getValue());
//        }
    }
//
//    @Benchmark
//    public String redisGetBenchmark10of1M() {
//        int t = random.nextInt(BIG_FILE_COUNT);
//        RBucket<String> cached = conversationStorage.redisson.getBucket("" + t);
//        String cachedValue = cached.get();
//
//        if (cachedValue.length() != 1071153) {
//            throw new RuntimeException("Not equal 1071153");
//        }
//
//        return cachedValue;
//    }
//
//    @Benchmark
//    public void redisPutBenchmark10of1M() {
//        int t = random.nextInt(BIG_FILE_COUNT);
//
//        var bucket = conversationStorage.redisson.getBucket("" + t);
//        bucket.set(payload1024kStr);
//
//    }
//

//    @Benchmark
//    public List<String> type0ListRecentDir() {
//        return conversationStorage.listConversations(getTestPath(0, true, true));
//    }
//
//    @Benchmark
//    public List<String> type1ListRecentDir() {
//        return conversationStorage.listConversations(getTestPath(1, true, true));
//    }
//
//    @Benchmark
//    public List<String> type2ListRecentDir() {
//        return conversationStorage.listConversations(getTestPath(2, true, true));
//    }
////
//    @Benchmark
//    public List<String> type0ListRandomDir() {
//        return conversationStorage.listConversations(genPath(0, false, true));
//    }
//
//    @Benchmark
//    public List<String> type1ListRandomDir() {
//        return conversationStorage.listConversations(genPath(1, false, true));
//    }
//
//    @Benchmark
//    public List<String> type2ListRandomDir() {
//        return conversationStorage.listConversations(genPath(2, false, true));
//    }
//
//    @Benchmark
//    public List<String> type0ListDirRealistic() {
//        return (random.nextInt(100) < 5)
//                ? conversationStorage.listConversations(genPath(0, false, true))
//                : conversationStorage.listConversations(genPath(0, true, true));
//    }
//
//    @Benchmark
//    public List<String> type1ListDirRealistic() {
//        return (random.nextInt(100) < 5)
//                ? conversationStorage.listConversations(genPath(1, false, true))
//                : conversationStorage.listConversations(genPath(1, true, true));
//    }
//
//    @Benchmark
//    public List<String> type2ListDirRealistic() {
//        return (random.nextInt(100) < 5)
//                ? conversationStorage.listConversations(genPath(2, false, true))
//                : conversationStorage.listConversations(genPath(2, true, true));
//    }
//
//    @Benchmark
//    public String type0AccessRecentFile() {
//        return conversationStorage.getConversation(getTestPath(0, true, false));
//    }
//
//    @Benchmark
//    public String type1AccessRecentFile() {
//        return conversationStorage.getConversation(getTestPath(1, true, false));
//    }
//
//    @Benchmark
//    public String type2AccessRecentFile() {
//        var r = conversationStorage.getConversation(getTestPath(2, true, false));
//        if (r.length() != 1071153) {
//            throw new RuntimeException("Not equal 1071153");
//        }
//        return r;
//    }

    @Benchmark
    public String type0ListDir() {
        String path = getTestPath(0, true, true);
        ListContainerOptions options = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(path));
        var result = conversationStorage.blobStore.list(conversationStorage.bucketName, options).stream()
                .map(StorageMetadata::getName).toList();

        return String.join(";", result);
    }


//    @Benchmark
//    public String type0AccessRandomFile() {
//        return conversationStorage.getConversation(genPath(0, false, false));
//    }
//
//    @Benchmark
//    public String type1AccessRandomFile() {
//        return conversationStorage.getConversation(genPath(1, false, false));
//    }
//
//    @Benchmark
//    public String type2AccessRandomFile() {
//        return conversationStorage.getConversation(genPath(2, false, false));
//    }
//
//    @Benchmark
//    public String type0AccessFileRealistic() {
//        return (random.nextInt(100) < 5)
//                ? conversationStorage.getConversation(genPath(0, false, false))
//                : conversationStorage.getConversation(genPath(0, true, false));
//    }
//
//    @Benchmark
//    public String type1AccessFileRealistic() {
//        return (random.nextInt(100) < 5)
//                ? conversationStorage.getConversation(genPath(1, false, false))
//                : conversationStorage.getConversation(genPath(1, true, false));
//    }
//
//    @Benchmark
//    public String type2AccessFileRealistic() {
//        return (random.nextInt(100) < 5)
//                ? conversationStorage.getConversation(genPath(2, false, false))
//                : conversationStorage.getConversation(genPath(2, true, false));
//    }

//    @Benchmark
//    public void type0StoreRandomFileDirectly() {
//        var filePath = genPath(0, false, false);
//
//        Blob blob = conversationStorage.blobStore.blobBuilder(filePath).payload(payload8k).contentLength(payload8k.length).build();
//        conversationStorage.blobStore.putBlob(conversationStorage.bucketName, blob);
//    }

//    @Benchmark
//    public void type0PopulateUser() {
//
////        final int userIndex = zipfDistribution[0].sample() + 50000;
////        String conversationsPath = userDir + "/conversations";
////        String userDir = String.format("user/%d", userIndex);
//        int numOfConversation = 4000;
//
//        int fakeUserDir = random.nextInt();
//        long before = System.currentTimeMillis();
//        System.out.format("user %s is started populating\n", fakeUserDir);
//        for (int c = 0; c < numOfConversation; c++) {
//            final int userIndex = zipfDistribution[0].sample() + 50000;
//            String userDir = String.format("user/%d", userIndex);
//            String conversationsPath = userDir + "/conversations";
//            String filePath = conversationsPath + "/" + String.format("c-%d.txt", c);
//
//            Blob blob = conversationStorage.blobStore.blobBuilder(filePath).payload(payload8k).contentLength(payload8k.length).build();
//            conversationStorage.blobStore.putBlob(conversationStorage.bucketName, blob);
//        }
//        System.out.format("user %s is completed in %d millis\n", fakeUserDir, (System.currentTimeMillis() - before));
//    }


}
