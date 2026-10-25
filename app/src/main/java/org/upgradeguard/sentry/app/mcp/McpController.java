package org.upgradeguard.sentry.app.mcp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.upgradeguard.sentry.app.api.AccessFilter;

@RestController
public class McpController {
    private final McpServer server;

    public McpController(McpServer server) {
        this.server = server;
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(HttpServletRequest request, @RequestBody String body) {
        Object granted = request.getAttribute(AccessFilter.PROJECTS_ATTRIBUTE);
        List<?> projects = granted instanceof List<?> list ? list : List.of();
        Optional<String> reply = server.handle(body, id -> projects.contains("*") || projects.contains(id));
        return reply.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.ACCEPTED).build());
    }
}
