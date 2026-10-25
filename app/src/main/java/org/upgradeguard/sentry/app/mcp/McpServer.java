package org.upgradeguard.sentry.app.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;
import org.upgradeguard.sentry.app.jobs.JobService;
import org.upgradeguard.sentry.app.jobs.ProjectService;
import org.upgradeguard.sentry.app.store.JobRepository;
import org.upgradeguard.sentry.app.store.Rows;
import org.upgradeguard.sentry.contract.Json;

@Component
public class McpServer {
    public static final String PROTOCOL = "2025-03-26";

    private final ProjectService projects;
    private final JobService jobs;
    private final JobRepository jobRows;

    public McpServer(ProjectService projects, JobService jobs, JobRepository jobRows) {
        this.projects = projects;
        this.jobs = jobs;
        this.jobRows = jobRows;
    }

    public Optional<String> handle(String body, Predicate<String> mayUseProject) {
        JsonNode message;
        try {
            message = Json.parse(body);
        } catch (IllegalArgumentException invalid) {
            return Optional.of(Json.text(failure(null, -32700, "parse error")));
        }
        JsonNode id = message.get("id");
        String method = message.path("method").asText("");
        if (id == null || id.isNull()) {
            return Optional.empty();
        }
        ObjectNode reply = Json.object();
        reply.put("jsonrpc", "2.0");
        reply.set("id", id);
        switch (method) {
            case "initialize" -> reply.set("result", initialize());
            case "ping" -> reply.set("result", Json.object());
            case "tools/list" -> reply.set("result", Json.object().set("tools", tools()));
            case "tools/call" -> reply.set("result", call(message.path("params"), mayUseProject));
            default -> {
                return Optional.of(Json.text(failure(id, -32601, "unknown method " + method)));
            }
        }
        return Optional.of(Json.text(reply));
    }

    private static ObjectNode failure(JsonNode id, int code, String message) {
        ObjectNode reply = Json.object();
        reply.put("jsonrpc", "2.0");
        if (id == null) {
            reply.putNull("id");
        } else {
            reply.set("id", id);
        }
        ObjectNode error = reply.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return reply;
    }

    private ObjectNode initialize() {
        ObjectNode result = Json.object();
        result.put("protocolVersion", PROTOCOL);
        result.putObject("capabilities").putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "sentry");
        info.put("version", "0.1.0");
        return result;
    }

    private ArrayNode tools() {
        ArrayNode tools = Json.array();
        tool(tools, "list_projects", "List the projects this token may use.", Json.object());
        tool(tools, "submit_job", "Queue a job and return its id straight away. operation is SNAPSHOT, REHEARSE_UPGRADE or REHEARSE_RECOVERY.",
                schema("project_id", "operation", "package", "from", "to", "snapshot_id", "execution"));
        tool(tools, "job_status", "Show the state of one job.", schema("job_id"));
        tool(tools, "job_report", "Show the verdict, the findings and the changed tests of a finished job.", schema("job_id"));
        tool(tools, "cancel_job", "Ask a queued or running job to stop.", schema("job_id"));
        tool(tools, "compare_snapshots", "Compare two snapshots, or the last passing one with the newest.", schema("project_id", "old", "new"));
        return tools;
    }

    private static ObjectNode schema(String... properties) {
        ObjectNode schema = Json.object();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (String name : properties) {
            props.putObject(name).put("type", "string");
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private static void tool(ArrayNode tools, String name, String description, ObjectNode schema) {
        ObjectNode tool = tools.addObject();
        tool.put("name", name);
        tool.put("description", description);
        if (!schema.has("type")) {
            schema.put("type", "object");
            schema.putObject("properties");
        }
        tool.set("inputSchema", schema);
    }

    private ObjectNode call(JsonNode params, Predicate<String> mayUseProject) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");
        try {
            String text = switch (name) {
                case "list_projects" -> listProjects(mayUseProject);
                case "submit_job" -> submit(arguments, mayUseProject);
                case "job_status" -> jobStatus(arguments, mayUseProject);
                case "job_report" -> jobReport(arguments, mayUseProject);
                case "cancel_job" -> cancel(arguments, mayUseProject);
                case "compare_snapshots" -> compare(arguments, mayUseProject);
                default -> throw new JobService.Rejected("unknown tool " + name);
            };
            return content(text, false);
        } catch (JobService.Rejected | IOException problem) {
            return content(String.valueOf(problem.getMessage()), true);
        }
    }

    private static ObjectNode content(String text, boolean error) {
        ObjectNode result = Json.object();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", error);
        return result;
    }

    private String listProjects(Predicate<String> may) {
        ArrayNode list = Json.array();
        projects.all().stream().filter(item -> may.test(item.id())).forEach(item -> list.addObject().put("id", item.id()).put("name", item.name()).put("ecosystem", item.ecosystem()));
        return Json.text(list);
    }

    private String submit(JsonNode arguments, Predicate<String> may) {
        String projectId = text(arguments, "project_id");
        requireProject(projectId, may);
        String id = jobs.submit(projectId, new JobService.Submission(text(arguments, "operation"), arguments.path("package").asText(null), arguments.path("from").asText(null),
                arguments.path("to").asText(null), arguments.path("snapshot_id").asText(null), arguments.path("execution").asText(null)));
        return Json.text(Json.object().put("job_id", id).put("note", "the job runs in the background; poll job_status"));
    }

    private Rows.Job accessibleJob(JsonNode arguments, Predicate<String> may) {
        Rows.Job job = jobs.find(text(arguments, "job_id")).orElseThrow(() -> new JobService.Rejected("unknown job"));
        requireProject(job.projectId(), may);
        return job;
    }

    private static void requireProject(String projectId, Predicate<String> may) {
        if (!may.test(projectId)) {
            throw new JobService.Rejected("this token cannot use project " + projectId);
        }
    }

    private String jobStatus(JsonNode arguments, Predicate<String> may) {
        Rows.Job job = accessibleJob(arguments, may);
        ObjectNode node = Json.object();
        node.put("job_id", job.id()).put("status", job.status()).put("operation", job.operation());
        if (job.stage() != null) {
            node.put("stage", job.stage());
        }
        if (job.verdict() != null) {
            node.put("verdict", job.verdict());
        }
        if (job.error() != null) {
            node.put("error", job.error());
        }
        return Json.text(node);
    }

    private String jobReport(JsonNode arguments, Predicate<String> may) throws IOException {
        Rows.Job job = accessibleJob(arguments, may);
        Optional<JsonNode> evidence = jobs.evidence(job.id());
        if (evidence.isEmpty()) {
            throw new JobService.Rejected("the job has no evidence yet (status " + job.status() + ")");
        }
        ObjectNode report = Json.object();
        report.put("verdict", evidence.get().path("verdict").path("decision").asText());
        report.set("findings", evidence.get().path("verdict").path("findings"));
        report.set("test_regressions", evidence.get().path("diff").path("tests").path("regressions"));
        report.set("dependency_changes", evidence.get().path("diff").path("dependencies").path("changed"));
        report.set("checked", evidence.get().path("coverage").path("checked"));
        report.set("not_checked", evidence.get().path("coverage").path("not_checked"));
        report.set("unavailable", evidence.get().path("coverage").path("unavailable"));
        return Json.text(report);
    }

    private String cancel(JsonNode arguments, Predicate<String> may) {
        Rows.Job job = accessibleJob(arguments, may);
        return Json.text(Json.object().put("cancelling", jobs.cancel(job.id())));
    }

    private String compare(JsonNode arguments, Predicate<String> may) throws IOException {
        String projectId = text(arguments, "project_id");
        requireProject(projectId, may);
        return Json.text(jobs.compare(projectId, arguments.path("old").asText(null), arguments.path("new").asText(null)));
    }

    private static String text(JsonNode arguments, String name) {
        String value = arguments.path(name).asText("");
        if (value.isBlank()) {
            throw new JobService.Rejected("missing " + name);
        }
        return value;
    }

    public List<String> toolNames() {
        List<String> names = new java.util.ArrayList<>();
        tools().forEach(item -> names.add(item.path("name").asText()));
        return names;
    }
}
