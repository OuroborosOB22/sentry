package org.upgradeguard.sentry.app.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.filter.OncePerRequestFilter;
import org.upgradeguard.sentry.app.config.SentryProperties;

@Component
public class AccessFilter extends OncePerRequestFilter {
    public static final String SESSION_ATTRIBUTE = "sentry.token";
    public static final String PROJECTS_ATTRIBUTE = "sentry.projects";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final SentryProperties properties;

    public AccessFilter(SentryProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String path = normalizedPath(request);
        addHeaders(response, isApi(path), isLanding(path));
        boolean hosted = !properties.auth().tokens().isEmpty();
        if (changesState(request) && crossSite(request)) {
            deny(response, 403, "cross-site requests are not accepted");
            return;
        }
        if (changesState(request) && isApi(path) && hasBody(request) && !jsonContent(request)) {
            deny(response, 415, "send application/json");
            return;
        }
        if (path.equals("/welcome") || path.equals("/welcome/")) {
            response.sendRedirect("/welcome/index.html");
            return;
        }
        if (isLanding(path)) {
            chain.doFilter(request, response);
            return;
        }
        if ((path.equals("/login") || path.equals("/logout")) && !isApi(path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!hosted) {
            if (!loopback(request.getRemoteAddr()) || !loopbackHost(request.getServerName())) {
                deny(response, 403, "this installation only answers on this machine until access tokens are configured");
                return;
            }
            request.setAttribute(PROJECTS_ATTRIBUTE, List.of("*"));
            chain.doFilter(request, response);
            return;
        }
        Optional<SentryProperties.Token> token = tokenFor(request, path);
        if (token.isEmpty()) {
            if (isApi(path)) {
                deny(response, 401, "a valid access token is required");
            } else {
                response.sendRedirect("/login");
            }
            return;
        }
        request.setAttribute(PROJECTS_ATTRIBUTE, token.get().projects().isEmpty() ? List.of("*") : token.get().projects());
        chain.doFilter(request, response);
    }

    private Optional<SentryProperties.Token> tokenFor(HttpServletRequest request, String path) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return match(header.substring(7).trim());
        }
        if (isApi(path)) {
            return Optional.empty();
        }
        HttpSession session = request.getSession(false);
        Object stored = session == null ? null : session.getAttribute(SESSION_ATTRIBUTE);
        return stored instanceof String text ? match(text) : Optional.empty();
    }

    static String normalizedPath(HttpServletRequest request) {
        UrlPathHelper helper = new UrlPathHelper();
        helper.setRemoveSemicolonContent(true);
        helper.setUrlDecode(true);
        String path = StringUtils.cleanPath(helper.getPathWithinApplication(request));
        return path.isEmpty() ? "/" : (path.startsWith("/") ? path : "/" + path);
    }

    static boolean isApi(String path) {
        return path.equals("/api") || path.startsWith("/api/") || path.equals("/mcp") || path.startsWith("/mcp/");
    }

    static boolean isLanding(String path) {
        return path.startsWith("/welcome/");
    }

    private static boolean changesState(HttpServletRequest request) {
        return !Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
    }

    private static boolean hasBody(HttpServletRequest request) {
        return request.getContentLengthLong() > 0 || request.getContentType() != null || request.getHeader("Transfer-Encoding") != null;
    }

    private static boolean jsonContent(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase().startsWith("application/json");
    }

    private static boolean crossSite(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !site.equals("same-origin") && !site.equals("none")) {
            return true;
        }
        String origin = request.getHeader("Origin");
        if (origin == null) {
            return false;
        }
        String own = request.getScheme() + "://" + request.getServerName() + ((request.getServerPort() == 80 && request.getScheme().equals("http"))
                || (request.getServerPort() == 443 && request.getScheme().equals("https")) ? "" : ":" + request.getServerPort());
        return !origin.equalsIgnoreCase(own);
    }

    public Optional<SentryProperties.Token> match(String supplied) {
        byte[] wanted = supplied.getBytes(StandardCharsets.UTF_8);
        Optional<SentryProperties.Token> found = Optional.empty();
        for (SentryProperties.Token token : properties.auth().tokens()) {
            if (MessageDigest.isEqual(token.token().getBytes(StandardCharsets.UTF_8), wanted)) {
                found = Optional.of(token);
            }
        }
        return found;
    }

    private static boolean loopback(String address) {
        return address != null && (address.equals("127.0.0.1") || address.equals("0:0:0:0:0:0:0:1") || address.equals("::1"));
    }

    private static boolean loopbackHost(String host) {
        return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase());
    }

    private static void deny(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\": \"" + message.replace("\"", "'") + "\"}");
    }

    private static void addHeaders(HttpServletResponse response, boolean api, boolean landing) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; font-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'" + (api ? "" : " 'unsafe-eval'") + "; "
                + "connect-src 'self'; frame-ancestors 'none'; base-uri " + (api ? "'none'" : "'self'") + "; object-src 'none'; form-action 'self'");
        if (landing) {
            response.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self'; font-src 'self'; style-src 'self'; script-src 'self'; "
                    + "frame-ancestors 'none'; base-uri 'none'; object-src 'none'; form-action 'none'");
        }
    }
}
