package com.example.systemlog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SystemlogApplication {
    public static void main(String[] args) throws IOException {
        SpringApplication application = new SpringApplication(SystemlogApplication.class);
        Map<String, Object> defaults = new LinkedHashMap<>();
        Path env = Path.of(".env");
        if (Files.exists(env)) {
            for (String line : Files.readAllLines(env)) {
                line = line.trim();
                int separator = line.indexOf('=');
                if (!line.startsWith("#") && separator > 0) {
                    String key = line.substring(0, separator).trim();
                    String value = line.substring(separator + 1).trim();
                    if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'")))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    defaults.put(key, value);
                }
            }
        }
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
