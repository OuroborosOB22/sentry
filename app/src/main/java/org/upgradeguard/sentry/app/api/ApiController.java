package org.upgradeguard.sentry.app.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.jobs.ProjectService;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.app.store.SnapshotRepository;
import org.upgradeguard.sentry.contract.Json;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final ProjectService projects;
    private final JobService jobs;
    private final JobRepository jobRows;
    private final SnapshotRepository snapshots;

    public ApiController(ProjectService projects, JobService jobs, JobRepository jobRows, SnapshotRepository snapshots) {
        this.projects = projects;
        this.jobs = jobs;
        this.jobRows = jobRows;
        this.snapshots = snapshots;
    }

    static class Forbidden extends RuntimeException {
    }

    static class Missing extends RuntimeException {
    }

    private Rows.Project project(HttpServletRequest request, String id) {
        Rows.Project project = projects.find(id).orElseThrow(Missing::new);
        if (!ProjectAccess.allowed(request, id)) {
            throw new Forbidden();
        }
        return project;
    }

    private Rows.Job job(HttpServletRequest request, String id) {
        Rows.Job job = jobs.find(id).orElseThrow(Missing::new);
        project(request, job.projectId());
        return job;
    }

    @GetMapping("/projects")
    public List<Views.ProjectView> listProjects(HttpServletRequest request) {
        return projects.all().stream().filter(item -> ProjectAccess.allowed(request, item.id())).map(Views.ProjectView::of).toList();
    }

    @PostMapping("/projects")
    public ResponseEntity<Views.ProjectView> register(HttpServletRequest request, @RequestBody Views.Registration body) {
        if (!ProjectAccess.allowed(request, "*") && !((List<?>) request.getAttribute(AccessFilter.PROJECTS_ATTRIBUTE)).contains("*")) {
            throw new Forbidden();
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Views.ProjectView.of(projects.register(body.name(), body.path())));
    }

    @GetMapping("/projects/{id}")
    public Views.ProjectView one(HttpServletRequest request, @PathVariable String id) {
        return Views.ProjectView.of(project(request, id));
    }

    @GetMapping("/projects/{id}/jobs")
    public List<Views.JobView> projectJobs(HttpServletRequest request, @PathVariable String id) {
        project(request, id);
        return jobs.forProject(id).stream().map(item -> Views.JobView.of(item, jobRows.findings(item.id()))).toList();
    }

    @PostMapping("/projects/{id}/jobs")
    public ResponseEntity<Views.Submitted> submit(HttpServletRequest request, @PathVariable String id, @RequestBody Views.SubmissionBody body) {
        project(request, id);
        String jobId = jobs.submit(id, new JobService.Submission(body.operation(), body.packageName(), body.fromVersion(), body.toVersion(), body.snapshotId(), body.execution()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Views.Submitted(jobId));
    }

    @GetMapping("/projects/{id}/snapshots")
    public List<Views.SnapshotView> projectSnapshots(HttpServletRequest request, @PathVariable String id) {
        project(request, id);
        return snapshots.forProject(id).stream().map(Views.SnapshotView::of).toList();
    }

    @GetMapping(value = "/projects/{id}/compare", produces = MediaType.APPLICATION_JSON_VALUE)
    public String compare(HttpServletRequest request, @PathVariable String id, @RequestParam(required = false) String old, @RequestParam(name = "new", required = false) String current)
            throws IOException {
        project(request, id);
        return Json.text(jobs.compare(id, old, current));
    }

    @GetMapping("/jobs/{id}")
    public Views.JobView jobStatus(HttpServletRequest request, @PathVariable String id) {
        Rows.Job found = job(request, id);
        return Views.JobView.of(found, jobRows.findings(id));
    }

    @GetMapping(value = "/jobs/{id}/evidence", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> evidence(HttpServletRequest request, @PathVariable String id) throws IOException {
        job(request, id);
        return jobs.evidence(id).map(node -> ResponseEntity.ok(Json.text(node))).orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).body("{\"error\": \"no evidence for this job yet\"}"));
    }

    @PostMapping("/jobs/{id}/cancel")
    public ResponseEntity<String> cancel(HttpServletRequest request, @PathVariable String id) {
        job(request, id);
        return jobs.cancel(id) ? ResponseEntity.accepted().body("{\"cancelling\": true}") : ResponseEntity.status(HttpStatus.CONFLICT).body("{\"error\": \"the job is not running or queued\"}");
    }

    @ExceptionHandler(JobService.Rejected.class)
    public ResponseEntity<String> rejected(JobService.Rejected problem) {
        return error(HttpStatus.BAD_REQUEST, problem.getMessage());
    }

    @ExceptionHandler(Forbidden.class)
    public ResponseEntity<String> forbidden() {
        return error(HttpStatus.FORBIDDEN, "this token cannot use that project");
    }

    @ExceptionHandler(Missing.class)
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
