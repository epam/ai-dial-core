package com.epam.aidial.core.server.data;

import com.epam.aidial.core.resourceservice.data.MetadataBase;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SharedResourcesResponse {
    Set<MetadataBase> resources;
}
