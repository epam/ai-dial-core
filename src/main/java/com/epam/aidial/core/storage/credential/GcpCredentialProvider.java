package com.epam.aidial.core.storage.credential;

import com.google.common.io.Files;
import lombok.SneakyThrows;
import org.jclouds.domain.Credentials;
import org.jclouds.googlecloud.GoogleCredentialsFromJson;

import java.io.File;
import java.util.Objects;
import java.util.function.Supplier;

import static java.nio.charset.StandardCharsets.UTF_8;

public class GcpCredentialProvider implements CredentialProvider {

    private final Credentials credentials;

    public GcpCredentialProvider(String identity, String pathToPrivateKey) {
        this.credentials = getCredentialsFromJsonKeyFile(Objects.requireNonNull(pathToPrivateKey, "Path to JSON key file must be provided"));
    }

    @Override
    public Credentials getCredentials() {
        return credentials;
    }

    @SneakyThrows
    private static Credentials getCredentialsFromJsonKeyFile(String filename) {
        String fileContents = Files.asCharSource(new File(filename), UTF_8).read();
        Supplier<Credentials> credentialSupplier = new GoogleCredentialsFromJson(fileContents);
        return credentialSupplier.get();
    }
}
