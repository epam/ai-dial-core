package com.epam.aidial.core.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.epam.aidial.core.storage.BlobStorage.buildListContainerOptions;

import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.impl.HostAndPortImpl;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.options.ListContainerOptions;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

public class ConversationStorage {

    private static final String STORE_QUEUE_KEY = "store-queue";
    private static final int SECONDS_TO_EXPIRE = 60*60;
    public static final String CONTAINER = "stagingdialtest";
    public static final String ACCESS_KEY = "";
    BlobStore blobStore;
    RedissonClient redisson;
    String bucketName;
    RDeque<String> storeQueue;

    public ConversationStorage(BlobStore blobStore, RedissonClient redisson, String bucketName) {
        this.blobStore = blobStore;
        this.redisson = redisson;
        this.bucketName = bucketName;

        storeQueue = redisson.getDeque(STORE_QUEUE_KEY);
    }

    public void storeConversation(String path, byte[] data) {

        storeQueue.add(String.format("%s;%s", path, new String(data)));
        var bucket = redisson.getBucket(path);
        bucket.set(new String(data));
        bucket.expire(Duration.ofSeconds(SECONDS_TO_EXPIRE));
    }

    public void storeQueueProcessing() {

        long start = -1;
        while (true) {
            String current = storeQueue.pollFirst();
            if (current != null) {

                if (start == -1) {
                    start = System.currentTimeMillis();
                }
                String[] pair = current.split(";", 2);
                String key = pair[0];
                byte[] value = pair[1].getBytes();

                blobStore.putBlob(bucketName, blobStore.blobBuilder(key)
                        .payload(new ByteArrayInputStream(value))
                        .contentLength(value.length)
                        .build());
                System.out.println(key + " is stored");
            } else {
                System.out.println("store thread sleeps. Store took: " + (System.currentTimeMillis() - start));
                start = -1;
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }


    public String getConversation(String path) {
        RBucket<String> cached =  redisson.getBucket(path);
        String cachedValue = cached.get();

        if (cachedValue != null) {
            System.out.println("Received " + path + " from cache");
            return cachedValue;
        }

        var blob = blobStore.getBlob(bucketName, path);
        try (var stream = blob.getPayload().openStream()) {
            var bytes = stream.readAllBytes();

            String value = new String(bytes);
            cached.set(value);
            cached.expire(Duration.ofSeconds(SECONDS_TO_EXPIRE));

            return value;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> listConversations(String path) {
        RBucket<String> cached = redisson.getBucket(path);
        String cachedValue = cached.get();

        if (cachedValue != null) {
            return List.of(cachedValue.split(";"));
        }

        ListContainerOptions options = buildListContainerOptions(BlobStorageUtil.normalizePathForQuery(path));
        var result = blobStore.list(bucketName, options).stream().map(StorageMetadata::getName).toList();
        cached.set(String.join(";", result));
        cached.expire(Duration.ofSeconds(SECONDS_TO_EXPIRE));

        return result;
    }



    public static void main(String[] args) throws InterruptedException {

//        Config config = new Config();
//        config.useClusterServers()
//                .setMasterConnectionPoolSize(10)
//                .setSlaveConnectionPoolSize(10)
//                .setNodeAddresses(List.of(
//                    "redis://localhost:6380",
//                    "redis://localhost:6381",
//                    "redis://localhost:6382",
//                    "redis://localhost:6383",
//                    "redis://localhost:6384",
//                    "redis://localhost:6385"));
  //      config.useSingleServer()
                //.setConnectionPoolSize(10)
//                .setAddress("redis://localhost:6379");
//        RedissonClient client = Redisson.create(config);

//        ContextBuilder builder = ContextBuilder.newBuilder("azureblob");
//        builder.credentials(ConversationStorage.CONTAINER, ConversationStorage.ACCESS_KEY);
//        var storeContext = builder.buildView(BlobStoreContext.class);
//        var blobStore = storeContext.getBlobStore();
//
//        var conversationStorage = new ConversationStorage(blobStore, client, "rail");
//        var storageThread = new Thread(conversationStorage::storeQueueProcessing);
//        storageThread.start();

//        for (int i = 0; i < 10; ++i) {
//            conversationStorage.storeConversation("user/50001/conversations/c-" + i + ".txt", ("test" + i).getBytes());
//        }

//        Thread.sleep(3000);

//        for (int i = 0; i < 10; ++i) {
//            var file = conversationStorage.getConversation("user/50001/conversations/c-" + i + ".txt");
//            System.out.println(new String(file));
//        }
//        storageThread.interrupt();
    }


}

