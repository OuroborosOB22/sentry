package org.upgradeguard.sentry.app.ui;

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinSession;
import java.util.List;
import org.upgradeguard.sentry.app.api.AccessFilter;

final class UiAccess {
    private static final String KEY = "sentry.projects";

    private UiAccess() {
    }

    static void remember() {
        VaadinRequest request = VaadinRequest.getCurrent();
        VaadinSession session = VaadinSession.getCurrent();
        if (request != null && session != null && request.getAttribute(AccessFilter.PROJECTS_ATTRIBUTE) instanceof List<?> list) {
            session.setAttribute(KEY, list);
        }
    }

    static boolean allowed(String projectId) {
        VaadinSession session = VaadinSession.getCurrent();
        Object granted = session == null ? null : session.getAttribute(KEY);
        return granted instanceof List<?> list && (list.contains("*") || list.contains(projectId));
    }

    static boolean administrator() {
        VaadinSession session = VaadinSession.getCurrent();
        return session != null && session.getAttribute(KEY) instanceof List<?> list && list.contains("*");
    }
}
