package org.upgradeguard.sentry.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.upgradeguard.sentry.app.provider.ExecutionProvider;

public class FakeProvider implements ExecutionProvider {
    String decision = "REJECT";
    List<String> script = new ArrayList<>(List.of("RUNNING", "SUCCEEDED"));
    boolean tamper;
    String snapshotMode = "";
    String projectSlug = "demo";
    boolean lose;
    boolean failSubmit;
    final AtomicInteger cancels = new AtomicInteger();
    final AtomicInteger cleanups = new AtomicInteger();
    final AtomicInteger submits = new AtomicInteger();
    int polls;

    @Override
    public String id() {
        return "FAKE";
    }

    @Override
    public ProviderCheck validate() {
        return new ProviderCheck(true, "fake", List.of());
    }

    @Override
    public String submit(JobSpec spec) throws IOException {
        submits.incrementAndGet();
        if (failSubmit) {
            throw new IOException("cannot start");
        }
        return spec.jobId();
    }

    @Override
    public ProviderStatus status(String handle) throws IOException {
        if (lose) {
            throw new UnknownHandleException(handle);
        }
        String state = script.get(Math.min(polls++, script.size() - 1));
        return new ProviderStatus(state, state.toLowerCase());
    }

    @Override
    public void cancel(String handle) {
        cancels.incrementAndGet();
        script = new ArrayList<>(List.of("FAILED"));
        polls = 0;
    }

    @Override
    public Path collect(String handle, Path destination) throws IOException {
        if (!snapshotMode.isEmpty()) {
            return SnapshotFolders.create(destination, projectSlug, snapshotMode);
        }
        EvidenceFolders.create(destination, handle, decision);
        if (tamper) {
            Files.writeString(destination.resolve("after/pytest.log"), "forged");
        }
        return destination;
    }

    @Override
    public void cleanup(String handle) {
        cleanups.incrementAndGet();
    }
}
