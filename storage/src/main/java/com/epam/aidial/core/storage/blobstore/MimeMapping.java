package com.epam.aidial.core.storage.blobstore;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@UtilityClass
public class MimeMapping {

    private final Map<String, String> mapping = new HashMap<>();

    static {
        loadMapping();
    }

    @SneakyThrows
    private void loadMapping() {
        try (InputStream stream = Objects.requireNonNull(MimeMapping.class.getResourceAsStream("/mime.types"))) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(" ");
                mapping.put(parts[0], parts[1]);
            }
        }
    }

    public String getMimeTypeForFilename(String filename) {
        int li = filename.lastIndexOf('.');
        if (li != -1 && li != filename.length() - 1) {
            String ext = filename.substring(li + 1);
            return mapping.get(ext);
        }
        return null;
    }
}
