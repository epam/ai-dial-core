package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;

public class CollectDeploymentsFn extends BaseRequestFunction<RequestObject> {

    public CollectDeploymentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        try {
            if (context.getDeployment() instanceof Application application) {
                shareApplicationDeployments(application);
            }
            return false;
        } catch (HttpException ex) {
            throw ex;
        } catch (ApplicationTypeResourceException ex) {
            throw new HttpException(HttpStatus.FAILED_DEPENDENCY, ex.getMessage() + " : " + ex.getResourceUri());
        } catch (Exception ex) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }
}
