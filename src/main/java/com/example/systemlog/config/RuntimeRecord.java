package com.example.systemlog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class RuntimeRecord {
    private final Path path=Path.of(".local","api-runtime.json");
    private final ObjectMapper json;
    private boolean owned;
    public RuntimeRecord(ObjectMapper json){this.json=json;}
    @EventListener public void ready(ApplicationReadyEvent event) throws Exception {
        if(!(event.getApplicationContext() instanceof ServletWebServerApplicationContext context))return;
        var process=ProcessHandle.current();
        var info=Map.of("pid",process.pid(),"executable",process.info().command().orElse("java"),
            "started_at",process.info().startInstant().orElse(Instant.now()).toString(),"port",context.getWebServer().getPort());
        Files.createDirectories(path.getParent());json.writeValue(path.toFile(),info);owned=true;
    }
    @PreDestroy public void remove() throws Exception {
        if(owned && Files.exists(path)) {
            var data=json.readTree(path.toFile());
            if(data.path("pid").asLong()==ProcessHandle.current().pid())Files.delete(path);
        }
    }
}
