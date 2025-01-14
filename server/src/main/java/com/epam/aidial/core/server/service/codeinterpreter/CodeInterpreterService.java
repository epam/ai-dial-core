package com.epam.aidial.core.server.service.codeinterpreter;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.AuthBucket;
import com.epam.aidial.core.server.data.ResourceTypes;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteRequest;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterExecuteResponse;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterFiles;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterInputFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterOutputFile;
import com.epam.aidial.core.server.data.codeinterpreter.CodeInterpreterSession;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationOperatorService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.ResourceNotFoundException;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.stream.BlobWriteStream;
import com.epam.aidial.core.server.vertx.stream.InputStreamReader;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.epam.aidial.core.server.util.ProxyUtil.convertToObject;
import static com.epam.aidial.core.server.util.ProxyUtil.convertToString;

@Slf4j
public class CodeInterpreterService {

    private final Vertx vertx;
    private final ResourceService resourceService;
    private final AccessService accessService;
    private final EncryptionService encryptionService;
    private final ApplicationOperatorService operatorService;
    private final RScoredSortedSet<String> activeSessions;
    private final CodeInterpreterClient client;
    private final Supplier<String> idGenerator;
    private final String sessionImage;
    private final long sessionTtl;
    private final int checkSize;

    public CodeInterpreterService(Vertx vertx, RedissonClient redisson,
                                  ResourceService resourceService, AccessService accessService,
                                  EncryptionService encryptionService, ApplicationOperatorService operatorService,
                                  Supplier<String> idGenerator, JsonObject settings) {
        String activeSessionsKey = BlobStorageUtil.toStoragePath(resourceService.getPrefix(), "active-code-interpreter-sessions");

        this.vertx = vertx;
        this.resourceService = resourceService;
        this.accessService = accessService;
        this.encryptionService = encryptionService;
        this.operatorService = operatorService;
        this.idGenerator = idGenerator;
        this.activeSessions = redisson.getScoredSortedSet(activeSessionsKey);
        this.sessionImage = settings.getString("sessionImage");
        this.sessionTtl = settings.getLong("sessionTtl", 600000L);
        this.checkSize = settings.getInteger("checkSize", 256);
        this.client = new CodeInterpreterClient(sessionTtl);

        if (isActive()) {
            long checkPeriod = settings.getLong("checkPeriod", 10000L);
            vertx.setPeriodic(checkPeriod, checkPeriod, ignore -> vertx.executeBlocking(this::checkSessions));
        }
    }

    private Void checkSessions() {
        log.debug("Checking active sessions");
        try {
            long now = System.currentTimeMillis();

            for (String url : activeSessions.valueRange(Double.NEGATIVE_INFINITY, true, now, true, 0, checkSize)) {
                log.debug("Checking active session: {}", url);
                ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
                Predicate<CodeInterpreterSession> ifExpired = session -> System.currentTimeMillis() - session.getUsedAt() >= sessionTtl;
                cleanupSession(resource, ifExpired);
            }
        } catch (Throwable e) {
            log.warn("Failed to check active sessions", e);
        }

        return null;
    }

    private void cleanupSession(ResourceDescriptor resource, Predicate<CodeInterpreterSession> predicate) {
        try (LockService.Lock lock = resourceService.tryLockResource(resource)) {
            if (lock == null) {
                return;
            }

            String json = resourceService.getResource(resource, EtagHeader.ANY, false);
            CodeInterpreterSession session = convertToObject(json, CodeInterpreterSession.class);

            if (session != null && predicate.test(session)) {
                operatorService.deleteCodeInterpreterDeployment(session.getDeploymentId());
                resourceService.deleteResource(resource, EtagHeader.ANY, false);
                session = null;
            }

            if (session == null) {
                activeSessions.remove(resource.getUrl());
            }
        } catch (Throwable e) {
            log.warn("Failed to cleanup active session", e);
        }
    }

    public CodeInterpreterSession touchSession(ProxyContext context, String sessionId) {
        verifyActive();
        verifySessionId(sessionId);

        ResourceDescriptor resource = sessionResource(context, sessionId);
        try (LockService.Lock lock = resourceService.lockResource(resource)) {
            String json = resourceService.getResource(resource, EtagHeader.ANY, false);
            CodeInterpreterSession session = convertToObject(json, CodeInterpreterSession.class);

            if (session == null) {
                throw new ResourceNotFoundException("Session is not found: " + sessionId);
            }

            if (session.getDeploymentUrl() == null) {
                throw new IllegalStateException("Session is not yet initialized: " + sessionId);
            }

            session.setUsedAt(System.currentTimeMillis());
            activeSessions.add(session.getUsedAt() + sessionTtl, resource.getUrl());
            resourceService.putResource(resource, convertToString(session), EtagHeader.ANY, false);
            return session;
        }
    }

    public CodeInterpreterSession openSession(ProxyContext context, String sessionId) {
        verifyActive();

        if (sessionId == null) {
            sessionId = idGenerator.get();
        }

        ResourceDescriptor resource = sessionResource(context, sessionId);
        CodeInterpreterSession session = new CodeInterpreterSession();
        session.setSessionId(sessionId);
        session.setDeploymentId(idGenerator.get());
        boolean cleanup = false;

        try (LockService.Lock lock = resourceService.lockResource(resource)) {
            String json = resourceService.getResource(resource, EtagHeader.ANY, false);
            CodeInterpreterSession existing = convertToObject(json, CodeInterpreterSession.class);
            if (existing != null) {
                throw new IllegalArgumentException("Session already exists: " + session.getSessionId());
            }

            cleanup = true;
            session.setUsedAt(System.currentTimeMillis());
            activeSessions.add(session.getUsedAt() + sessionTtl, resource.getUrl());
            resourceService.putResource(resource, convertToString(session), EtagHeader.ANY, false);

            String deploymentUrl = operatorService.createCodeInterpreterDeployment(session.getDeploymentId(), sessionImage);
            session.setDeploymentUrl(deploymentUrl);
            session.setUsedAt(System.currentTimeMillis());

            activeSessions.add(session.getUsedAt() + sessionTtl, resource.getUrl());
            resourceService.putResource(resource, convertToString(session), EtagHeader.ANY, false);
        } catch (Throwable error) {
            if (cleanup) {
                Predicate<CodeInterpreterSession> ifMatch = candidate -> Objects.equals(candidate.getDeploymentId(), session.getDeploymentId());
                cleanupSession(resource, ifMatch);
            }

            throw error;
        }

        return session;
    }

    public CodeInterpreterSession closeSession(ProxyContext context, String sessionId) {
        verifyActive();
        verifySessionId(sessionId);

        ResourceDescriptor resource = sessionResource(context, sessionId);
        try (LockService.Lock lock = resourceService.lockResource(resource)) {
            String json = resourceService.getResource(resource, EtagHeader.ANY, false);
            CodeInterpreterSession session = convertToObject(json, CodeInterpreterSession.class);

            if (session == null) {
                throw new ResourceNotFoundException("Session is not found: " + sessionId);
            }

            operatorService.deleteCodeInterpreterDeployment(session.getDeploymentId());
            resourceService.deleteResource(resource, EtagHeader.ANY, false);
            activeSessions.remove(resource.getUrl());
            return session;
        }
    }

    public CodeInterpreterExecuteResponse executeCode(ProxyContext context, CodeInterpreterExecuteRequest request) {
        verifyActive();
        verifyCode(request);

        boolean anonymous = (request.getSessionId() == null);
        CodeInterpreterSession session;

        if (anonymous) {
            session = openSession(context, null);
        } else {
            session = touchSession(context, request.getSessionId());
        }

        try {
            if (request.getInputFiles() != null) {
                for (CodeInterpreterInputFile input : request.getInputFiles()) {
                    input.setSessionId(session.getSessionId());
                    transferInputFile(context, input);
                }
            }

            CodeInterpreterExecuteResponse response = client.executeCode(session, request.getCode());

            if (request.getOutputFiles() != null) {
                for (CodeInterpreterOutputFile output : request.getOutputFiles()) {
                    output.setSessionId(session.getSessionId());
                    transferOutputFile(context, output);
                }
            }

            return response;
        } finally {
            if (anonymous) {
                closeSession(context, session.getSessionId());
            }
        }
    }

    @SneakyThrows
    public CodeInterpreterFile uploadFile(ProxyContext context, String sessionId, String path, InputStream stream) {
        try (InputStream resource = stream) {
            verifyActive();
            verifySessionId(sessionId);
            verifyPath(path);

            CodeInterpreterSession session = touchSession(context, sessionId);
            return client.uploadFile(session, stream, path);
        }
    }

    public <R> R downloadFile(ProxyContext context, String sessionId, String path, CodeInterpreterClient.DownloadFileFunction<R> function) {
        verifyActive();
        verifySessionId(sessionId);
        verifyPath(path);

        CodeInterpreterSession session = touchSession(context, sessionId);
        return client.downloadFile(session, path, function);
    }

    public CodeInterpreterFiles listFiles(ProxyContext context, String sessionId) {
        verifyActive();
        verifySessionId(sessionId);

        CodeInterpreterSession session = touchSession(context, sessionId);
        return client.listFiles(session);
    }

    @SneakyThrows
    public CodeInterpreterFile transferInputFile(ProxyContext context, CodeInterpreterInputFile file) {
        verifyActive();
        verifySessionId(file.getSessionId());
        verifyPath(file.getTargetPath());

        ResourceDescriptor resource = verifyFile(context, file.getSourceUrl(), true);
        ResourceService.ResourceStream input = resourceService.getResourceStream(resource, EtagHeader.ANY);

        if (input == null) {
            throw new ResourceNotFoundException("File is not found: " + resource.getUrl());
        }

        return uploadFile(context, file.getSessionId(), file.getTargetPath(), input.inputStream());
    }

    public FileMetadata transferOutputFile(ProxyContext context, CodeInterpreterOutputFile file) {
        verifyActive();
        verifySessionId(file.getSessionId());
        verifyPath(file.getSourcePath());

        ResourceDescriptor resource = verifyFile(context, file.getTargetUrl(), false);

        return downloadFile(context, file.getSessionId(), file.getSourcePath(), (input, size) -> {
            BlobWriteStream output = new BlobWriteStream(vertx, resourceService,
                    context.getProxy().getStorage(), resource, EtagHeader.ANY, null);

            return new InputStreamReader(vertx, input)
                    .pipe()
                    .endOnFailure(false)
                    .to(output)
                    .onFailure(output::abortUpload)
                    .map(success -> output.getMetadata());
        });
    }

    private ResourceDescriptor sessionResource(ProxyContext context, String sessionId) {
        AuthBucket bucket = BucketBuilder.buildBucket(context);

        try {
            String path = (bucket.getAppBucket() == null)
                    ? ("user/" + sessionId)
                    : ("app/" + bucket.getAppBucket() + "/" + sessionId);

            ResourceDescriptor resource = ResourceDescriptorFactory.fromEncoded(ResourceTypes.CODE_INTERPRETER_SESSION,
                    bucket.getUserBucket(), bucket.getUserBucketLocation(), path);

            if (resource.isFolder()) {
                throw new IllegalArgumentException("Invalid resource");
            }

            return resource;
        } catch (Throwable e) {
            throw new IllegalArgumentException("Invalid sessionId: " + sessionId);
        }
    }

    private boolean isActive() {
        return sessionImage != null && operatorService.isActive();
    }

    private void verifyActive() {
        if (!isActive()) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "Code interpreter is not available");
        }
    }

    private ResourceDescriptor verifyFile(ProxyContext context, String url, boolean input) {
        ResourceDescriptor resource;
        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
            if (resource.getType() != ResourceTypes.FILE) {
                throw new IllegalArgumentException();
            }
        } catch (Throwable e) {
            throw new IllegalArgumentException("Bad file url:" + url);
        }

        boolean isAccessible = input
                ? accessService.hasReadAccess(resource, context)
                : accessService.hasWriteAccess(resource, context);

        if (!isAccessible) {
            throw new PermissionDeniedException("File is not accessible: " + resource.getUrl());
        }

        return resource;
    }

    private static void verifySessionId(String sessionId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("Missing sessionId");
        }
    }

    private static void verifyCode(CodeInterpreterExecuteRequest request) {
        if (request.getCode() == null) {
            throw new IllegalArgumentException("Missing code");
        }
    }

    private static void verifyPath(String path) {
        try {
            Path ignore = Path.of(path);
        } catch (Throwable e) {
            throw new IllegalArgumentException("Bad file path:" + path);
        }
    }
}