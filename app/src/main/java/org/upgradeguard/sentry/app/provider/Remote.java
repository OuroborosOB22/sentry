package org.upgradeguard.sentry.app.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface Remote {
    record Answer(int exitCode, String output) {
    }

    Answer run(List<String> workerArguments) throws IOException;

    void upload(Path local, String remoteRelativePath) throws IOException;

    void download(String remoteRelativePath, Path local) throws IOException;
}
