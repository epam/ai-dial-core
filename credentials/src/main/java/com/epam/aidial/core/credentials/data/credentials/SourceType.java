package com.epam.aidial.core.credentials.data.credentials;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SourceType {
    STORAGE("storage"),
    CONFIG("config");

    private final String value;

}
