package com.epam.aidial.core.openapi;

import com.epam.aidial.core.server.controller.ApplicationController;
import com.epam.aidial.core.server.controller.ApplicationMcpProxyController;
import com.epam.aidial.core.server.controller.ApplicationTypeSchemaController;
import com.epam.aidial.core.server.controller.BucketController;
import com.epam.aidial.core.server.controller.ClientChannelController;
import com.epam.aidial.core.server.controller.CodeInterpreterController;
import com.epam.aidial.core.server.controller.ConfigController;
import com.epam.aidial.core.server.controller.ConsentController;
import com.epam.aidial.core.server.controller.DeploymentController;
import com.epam.aidial.core.server.controller.DeploymentFeatureController;
import com.epam.aidial.core.server.controller.DeploymentPostController;
import com.epam.aidial.core.server.controller.DownloadFileController;
import com.epam.aidial.core.server.controller.FileMetadataController;
import com.epam.aidial.core.server.controller.InvitationController;
import com.epam.aidial.core.server.controller.LimitController;
import com.epam.aidial.core.server.controller.ModelController;
import com.epam.aidial.core.server.controller.NotificationController;
import com.epam.aidial.core.server.controller.PerRequestPermissionController;
import com.epam.aidial.core.server.controller.PublicationController;
import com.epam.aidial.core.server.controller.RateResponseController;
import com.epam.aidial.core.server.controller.ResourceController;
import com.epam.aidial.core.server.controller.ResourceCredentialsController;
import com.epam.aidial.core.server.controller.ResourceOperationController;
import com.epam.aidial.core.server.controller.ResponsesController;
import com.epam.aidial.core.server.controller.ShareController;
import com.epam.aidial.core.server.controller.ToolSetController;
import com.epam.aidial.core.server.controller.ToolSetMcpProxyController;
import com.epam.aidial.core.server.controller.ToolSetToolsController;
import com.epam.aidial.core.server.controller.UploadFileController;
import com.epam.aidial.core.server.controller.UserInfoController;
import com.epam.aidial.core.server.openapi.ApiOperation;
import com.epam.aidial.core.server.openapi.ApiOperations;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AnnotationEndpointCollector {

    private static final List<Class<?>> CONTROLLER_CLASSES = List.of(
            ApplicationController.class,
            ApplicationMcpProxyController.class,
            ApplicationTypeSchemaController.class,
            BucketController.class,
            ClientChannelController.class,
            CodeInterpreterController.class,
            ConfigController.class,
            ConsentController.class,
            DeploymentController.class,
            DeploymentFeatureController.class,
            DeploymentPostController.class,
            DownloadFileController.class,
            FileMetadataController.class,
            InvitationController.class,
            LimitController.class,
            ModelController.class,
            NotificationController.class,
            PerRequestPermissionController.class,
            PublicationController.class,
            RateResponseController.class,
            ResourceController.class,
            ResourceCredentialsController.class,
            ResourceOperationController.class,
            ResponsesController.class,
            ShareController.class,
            ToolSetController.class,
            ToolSetMcpProxyController.class,
            ToolSetToolsController.class,
            UploadFileController.class,
            UserInfoController.class
    );

    private AnnotationEndpointCollector() {
    }

    public static List<EndpointMetadata.Endpoint> collect() {
        List<EndpointMetadata.Endpoint> endpoints = new ArrayList<>();

        for (Class<?> controllerClass : CONTROLLER_CLASSES) {
            for (Method method : controllerClass.getDeclaredMethods()) {
                collectFromMethod(method, endpoints);
            }
        }

        return Collections.unmodifiableList(endpoints);
    }

    private static void collectFromMethod(Method method, List<EndpointMetadata.Endpoint> endpoints) {
        // Check for @ApiOperations container first
        ApiOperations container = method.getAnnotation(ApiOperations.class);
        if (container != null) {
            for (ApiOperation op : container.value()) {
                endpoints.add(toEndpoint(op));
            }
        }

        // Check for individual @ApiOperation annotations
        ApiOperation[] operations = method.getAnnotationsByType(ApiOperation.class);
        if (container == null && operations.length > 0) {
            for (ApiOperation op : operations) {
                endpoints.add(toEndpoint(op));
            }
        }
    }

    private static EndpointMetadata.Endpoint toEndpoint(ApiOperation op) {
        Type requestBody = op.requestBody() == Void.class ? null : op.requestBody();
        Type responseBody = resolveResponseType(op);
        return new EndpointMetadata.Endpoint(
                op.method(),
                op.path(),
                op.operationId(),
                requestBody,
                responseBody,
                op.tags(),
                op.contentType()
        );
    }

    private static Type resolveResponseType(ApiOperation op) {
        Class<?> responseBody = op.responseBody();
        Class<?> responseWrapper = op.responseWrapper();

        if (responseBody == Void.class) {
            return null;
        }

        if (responseWrapper != Void.class) {
            return EndpointMetadata.paramType(responseWrapper, responseBody);
        }

        return responseBody;
    }
}
