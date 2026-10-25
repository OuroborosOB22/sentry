package org.upgradeguard.sentry.app.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

final class ProjectAccess {
    private ProjectAccess() {
    }

    @SuppressWarnings("unchecked")
    static boolean allowed(HttpServletRequest request, String projectId) {
        Object granted = request.getAttribute(AccessFilter.PROJECTS_ATTRIBUTE);
        if (!(granted instanceof List<?> list)) {
            return false;
        }
        return list.contains("*") || list.contains(projectId);
    }
}
