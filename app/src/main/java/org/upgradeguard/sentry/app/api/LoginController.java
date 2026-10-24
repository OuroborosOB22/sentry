package org.upgradeguard.sentry.app.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

@RestController
public class LoginController {
    private final AccessFilter filter;

    public LoginController(AccessFilter filter) {
        this.filter = filter;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String form(HttpServletRequest request) {
        String csrf = java.util.UUID.randomUUID().toString();
        request.getSession(true).setAttribute("sentry.csrf", csrf);
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Sentry login</title>"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body style=\"font-family:sans-serif;max-width:24rem;margin:4rem auto\">"
                + "<h1>Sentry</h1><form method=\"post\" action=\"/login\"><input type=\"hidden\" name=\"csrf\" value=\"" + HtmlUtils.htmlEscape(csrf) + "\">"
                + "<label>Access token<br><input name=\"token\" type=\"password\" autocomplete=\"off\" required style=\"width:100%\"></label>"
                + "<p><button type=\"submit\">Log in</button></p></form></body></html>";
    }

    @PostMapping("/login")
    public void submit(HttpServletRequest request, HttpServletResponse response, @RequestParam String token, @RequestParam String csrf) throws IOException {
        Object expected = request.getSession(true).getAttribute("sentry.csrf");
        if (!(expected instanceof String text) || !text.equals(csrf)) {
            response.sendError(403);
            return;
        }
        if (filter.match(token).isEmpty()) {
            response.sendRedirect("/login?failed");
            return;
        }
        request.changeSessionId();
        request.getSession().setAttribute(AccessFilter.SESSION_ATTRIBUTE, token);
        response.sendRedirect("/");
    }

    @PostMapping("/logout")
    public void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        response.sendRedirect("/login");
    }
}
