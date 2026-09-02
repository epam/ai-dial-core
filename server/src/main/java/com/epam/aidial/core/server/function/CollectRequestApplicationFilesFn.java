package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.validation.ApplicationTypeResourceException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class CollectRequestApplicationFilesFn extends BaseRequestFunction<RequestObject> {
    public CollectRequestApplicationFilesFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        try {
            Deployment deployment = context.getDeployment();
            if (!(deployment instanceof Application application && application.hasApplicationTypeSchemaId())) {
                return false;
            }
            if (application.getApplicationProperties() == null) {
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, "Typed application's properties not set");
            }
            shareApplicationFiles(application);
            shareApplicationPrompts(application);
            shareApplicationSkills(application);
            return false;
        } catch (HttpException ex) {
            throw ex;
        } catch (ApplicationTypeResourceException ex) {
            throw new HttpException(HttpStatus.FORBIDDEN, ex.getMessage() + " : " + ex.getResourceUri());
        } catch (Exception e) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

}
