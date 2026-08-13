package com.epam.aidial.core.openapi;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.server.controller.AdminApplyController;
import com.epam.aidial.core.server.controller.AdminHealthConfigController;
import com.epam.aidial.core.server.controller.AdminValidateController;
import com.epam.aidial.core.server.controller.ApplicationController;
import com.epam.aidial.core.server.controller.ApplicationMcpProxyController;
import com.epam.aidial.core.server.controller.ApplicationTypeSchemaController;
import com.epam.aidial.core.server.controller.BucketController;
import com.epam.aidial.core.server.controller.CatalogSchemaController;
import com.epam.aidial.core.server.controller.ClientChannelController;
import com.epam.aidial.core.server.controller.CodeInterpreterController;
import com.epam.aidial.core.server.controller.ComplexResourceController;
import com.epam.aidial.core.server.controller.ComplexResourceMetadataController;
import com.epam.aidial.core.server.controller.ConfigController;
import com.epam.aidial.core.server.controller.ConfigResourceController;
import com.epam.aidial.core.server.controller.ConfigResourceMetadataController;
import com.epam.aidial.core.server.controller.ConsentController;
import com.epam.aidial.core.server.controller.DeploymentController;
import com.epam.aidial.core.server.controller.DeploymentFeatureController;
import com.epam.aidial.core.server.controller.DeploymentPostController;
import com.epam.aidial.core.server.controller.DownloadFileController;
import com.epam.aidial.core.server.controller.ExternalServiceCredentialsController;
import com.epam.aidial.core.server.controller.ExternalServiceManagementController;
import com.epam.aidial.core.server.controller.FileConfigController;
import com.epam.aidial.core.server.controller.FileMetadataController;
import com.epam.aidial.core.server.controller.InvitationController;
import com.epam.aidial.core.server.controller.LimitController;
import com.epam.aidial.core.server.controller.McpResourceController;
import com.epam.aidial.core.server.controller.ModelController;
import com.epam.aidial.core.server.controller.NotificationController;
import com.epam.aidial.core.server.controller.OfflineCredentialsController;
import com.epam.aidial.core.server.controller.PerRequestPermissionController;
import com.epam.aidial.core.server.controller.PublicationController;
import com.epam.aidial.core.server.controller.RateResponseController;
import com.epam.aidial.core.server.controller.ResourceController;
import com.epam.aidial.core.server.controller.ResourceCredentialsController;
import com.epam.aidial.core.server.controller.ResourceOperationController;
import com.epam.aidial.core.server.controller.ResponseItemController;
import com.epam.aidial.core.server.controller.ResponsesController;
import com.epam.aidial.core.server.controller.ShareController;
import com.epam.aidial.core.server.controller.ToolSetController;
import com.epam.aidial.core.server.controller.ToolSetMcpProxyController;
import com.epam.aidial.core.server.controller.ToolSetRepairController;
import com.epam.aidial.core.server.controller.ToolSetToolsController;
import com.epam.aidial.core.server.controller.UploadFileController;
import com.epam.aidial.core.server.controller.UserInfoController;
import com.epam.aidial.core.server.controller.anthropic.MessagesController;
import com.epam.aidial.core.server.controller.anthropic.MessagesCountTokensController;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public final class AnnotationEndpointCollector {

    private static final List<Class<?>> CONTROLLER_CLASSES = List.of(
            AdminApplyController.class,
            AdminHealthConfigController.class,
            AdminValidateController.class,
            ApplicationController.class,
            ApplicationMcpProxyController.class,
            McpResourceController.class,
            ApplicationTypeSchemaController.class,
            BucketController.class,
            CatalogSchemaController.class,
            ClientChannelController.class,
            CodeInterpreterController.class,
            ConfigController.class,
            ConfigResourceController.class,
            ConfigResourceMetadataController.class,
            ConsentController.class,
            DeploymentController.class,
            DeploymentFeatureController.class,
            DeploymentPostController.class,
            DownloadFileController.class,
            ExternalServiceCredentialsController.class,
            ExternalServiceManagementController.class,
            FileConfigController.class,
            FileMetadataController.class,
            ComplexResourceController.class,
            ComplexResourceMetadataController.class,
            InvitationController.class,
            LimitController.class,
            MessagesController.class,
            MessagesCountTokensController.class,
            ModelController.class,
            NotificationController.class,
            OfflineCredentialsController.class,
            PerRequestPermissionController.class,
            PublicationController.class,
            RateResponseController.class,
            ResourceController.class,
            ResourceCredentialsController.class,
            ResourceOperationController.class,
            ResponseItemController.class,
            ResponsesController.class,
            ShareController.class,
            ToolSetController.class,
            ToolSetMcpProxyController.class,
            ToolSetRepairController.class,
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

        if (endpoints.isEmpty()) {
            throw new IllegalStateException(
                    "OpenAPI endpoint collection failed: Zero annotated operations detected. Check controller registrations."
            );
        }

        return Collections.unmodifiableList(endpoints);
    }

    private static void collectFromMethod(Method method, List<EndpointMetadata.Endpoint> endpoints) {
        ApiParameter[] methodParameters = method.getAnnotationsByType(ApiParameter.class);
        ApiResponse[] methodResponses = method.getAnnotationsByType(ApiResponse.class);

        ApiOperation[] operations = method.getAnnotationsByType(ApiOperation.class);
        boolean hasOperations = operations.length > 0;

        if (hasOperations) {
            for (ApiOperation op : operations) {
                endpoints.add(toEndpoint(
                        op,
                        mergeParameters(methodParameters, op.parameters()),
                        mergeResponses(methodResponses, op.responses())));
            }
        }

        if (!hasOperations && (methodParameters.length > 0 || methodResponses.length > 0)) {
            log.warn("Dangling OpenAPI metadata discovered: Method '{}.{}' has @ApiParameter or @ApiResponse "
                            + "annotations but is missing mandatory @ApiOperation/@ApiOperations root trigger. "
                            + "This block will be completely ignored during specification assembly.",
                    method.getDeclaringClass().getSimpleName(), method.getName());
        }
    }

    private static ApiParameter[] mergeParameters(ApiParameter[] methodParameters, ApiParameter[] operationParameters) {
        if (methodParameters.length == 0) {
            return operationParameters;
        }
        if (operationParameters.length == 0) {
            return methodParameters;
        }
        ApiParameter[] merged = new ApiParameter[methodParameters.length + operationParameters.length];
        System.arraycopy(methodParameters, 0, merged, 0, methodParameters.length);
        System.arraycopy(operationParameters, 0, merged, methodParameters.length, operationParameters.length);
        return merged;
    }

    private static ApiResponse[] mergeResponses(ApiResponse[] methodResponses, ApiResponse[] operationResponses) {
        if (operationResponses.length > 0) {
            return operationResponses;
        }
        return methodResponses;
    }

    private static EndpointMetadata.Endpoint toEndpoint(
            ApiOperation op,
            ApiParameter[] parameters,
            ApiResponse[] responses
    ) {
        return new EndpointMetadata.Endpoint(
                op.method(),
                op.path(),
                op.operationId(),
                op.requestBody(),
                op.tags(),
                op.contentType(),
                parameters,
                responses,
                op.extensions()
        );
    }
}