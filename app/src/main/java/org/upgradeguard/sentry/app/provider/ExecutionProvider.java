package org.upgradeguard.sentry.app.provider;

import java.io.IOException;
import java.nio.file.Path;

public interface ExecutionProvider {
    String id();

    ProviderCheck validate();

    String submit(JobSpec spec) throws IOException;

    ProviderStatus status(String handle) throws IOException;

    void cancel(String handle) throws IOException;

    Path collect(String handle, Path destination) throws IOException;

    void cleanup(String handle) throws IOException;

    record JobSpec(String jobId, String operation, String ecosystem, String projectSlug, Path projectPath, String packageName, String fromVersion,
                   String toVersion, Path snapshotStore, String snapshotId, Path workDir) {
    }

    record ProviderCheck(boolean usable, String summary, java.util.List<String> details) {
    }

    record ProviderStatus(String state, String message) {
        public static final String RUNNING = "RUNNING";
        public static final String SUCCEEDED = "SUCCEEDED";
        public static final String FAILED = "FAILED";

        public boolean finished() {
            return !RUNNING.equals(state);
        }
    }

    class UnknownHandleException extends IOException {
        public UnknownHandleException(String handle) {
            super("this process does not know job " + handle);
        }
    }
}
