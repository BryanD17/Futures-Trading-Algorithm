package com.topstep.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiBackendApplication {

    public static void main(String[] args) throws Exception {
        Path dataDir = Paths.get(System.getProperty("user.home"), "topstep-trading");
        Files.createDirectories(dataDir);
        SpringApplication.run(ApiBackendApplication.class, args);
    }
}
