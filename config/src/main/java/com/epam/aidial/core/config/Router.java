package com.epam.aidial.core.config;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A deployment that picks the deployment serving a request. It declares no field of its own: the fields of
 * another deployment kind ({@code upstreams}, {@code limits}, {@code pricing}) are rejected by the config mapper
 * because this class does not declare them.
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class Router extends Deployment {
}
