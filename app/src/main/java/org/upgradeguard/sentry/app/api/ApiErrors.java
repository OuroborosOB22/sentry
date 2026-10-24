package org.upgradeguard.sentry.app.api;

import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.contract.Json;

@RestControllerAdvice(basePackages = {"org.upgradeguard.sentry.app.api", "org.upgradeguard.sentry.app.mcp"})
public class ApiErrors {
    @ExceptionHandler(JobService.Rejected.class)
    public ResponseEntity<String> rejected(JobService.Rejected problem) {
        return error(HttpStatus.BAD_REQUEST, problem.getMessage());
    }

    @ExceptionHandler(ApiController.Forbidden.class)
    public ResponseEntity<String> forbidden() {
        return error(HttpStatus.FORBIDDEN, "this token cannot use that");
    }

    @ExceptionHandler(ApiController.Missing.class)
    public ResponseEntity<String> missing() {
        return error(HttpStatus.NOT_FOUND, "not found");
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<String> unreadable(IOException problem) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "the stored data could not be read");
    }

    private static ResponseEntity<String> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Json.text(Json.object().put("error", message)));
    }
}
