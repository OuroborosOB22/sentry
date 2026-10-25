package org.upgradeguard.sentry.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.upgradeguard.sentry.app.api.AccessFilter;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:landing;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/landing-test-data",
        "sentry.auth.tokens[0].token=admin-token-for-everything"})
class LandingPageTest {
    @Autowired
    AccessFilter filter;

    private MockHttpServletResponse through(String method, String path, MockFilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("203.0.113.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void theLandingPageNeedsNoTokenEvenWhenTokensAreConfigured() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = through("GET", "/welcome/index.html", chain);
        assertNotNull(chain.getRequest());
        assertEquals(200, response.getStatus());
        MockFilterChain closed = new MockFilterChain();
        assertEquals(302, through("GET", "/", closed).getStatus());
        assertNull(closed.getRequest());
    }

    @Test
    void onlyTheNamedLandingFilesAreOpenAndOnlyForReading() throws Exception {
        for (String path : new String[] {"/welcome/other.html", "/welcome/../settings", "/welcome/fonts/", "/welcome/index.html/x"}) {
            MockFilterChain chain = new MockFilterChain();
            assertEquals(302, through("GET", path, chain).getStatus());
            assertNull(chain.getRequest());
        }
        MockFilterChain post = new MockFilterChain();
        assertNull(post.getRequest());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/welcome/index.html");
        request.setRemoteAddr("203.0.113.9");
        request.setParameter("v-r", "uidl");
        MockFilterChain vaadin = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), vaadin);
        assertNull(vaadin.getRequest());
    }

    @Test
    void theShortAddressRedirectsToTheLandingPage() throws Exception {
        assertEquals("/welcome/index.html", through("GET", "/welcome", new MockFilterChain()).getRedirectedUrl());
    }

    @Test
    void theLandingPageGetsAStrictPolicy() throws Exception {
        String policy = through("GET", "/welcome/index.html", new MockFilterChain()).getHeader("Content-Security-Policy");
        assertTrue(policy.contains("script-src 'self'"));
        assertFalse(policy.contains("unsafe"));
    }

    @Test
    void theApiAndTheAppStayClosedNextToIt() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        assertEquals(401, through("GET", "/api/projects", chain).getStatus());
        assertEquals(401, through("GET", "/welcome/../api/projects", chain).getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void thePageFilesHaveNoInlineCodeAndNoRemoteAddresses() throws Exception {
        String page = new String(new ClassPathResource("META-INF/resources/welcome/index.html").getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(page.contains("<script>"));
        assertFalse(page.contains("style="));
        assertFalse(page.contains("https://"));
        assertTrue(page.contains("href=\"/\""));
    }
}
