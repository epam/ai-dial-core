package com.epam.aidial.core.credentials.data.credentials;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AuthorizationHeader {

    private String headerName;
    private String headerValue;
}
