package com.epam.aidial.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TestFileGenerator {
    public static void main(String[] args) throws IOException {
        String string = "0123456789\n".repeat(100_000);
        Files.writeString(Paths.get("text.txt"), string);
    }
}
