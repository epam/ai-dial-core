package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Encryption {
    String password;
    String salt;
}
