package org.upgradeguard.sentry.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.upgradeguard.sentry.app.api.AccessFilter;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:landinglocal;DB_CLOSE_DELAY=-1", "sentry.data-dir=target/landing-local-test-data"})
class LandingLocalOnlyTest {
    @Autowired
    AccessFilter filter;

    @Test
    void withoutTokensTheLandingPageAnswersOnlyOnThisMachine() throws Exception {
        MockHttpServletRequest remote = new MockHttpServletRequest("GET", "/welcome/index.html");
        remote.setRemoteAddr("203.0.113.9");
        MockFilterChain refused = new MockFilterChain();
        assertEquals(403, answer(remote, refused));
        assertNull(refused.getRequest());
        MockHttpServletRequest local = new MockHttpServletRequest("GET", "/welcome/index.html");
        local.setRemoteAddr("127.0.0.1");
        MockFilterChain allowed = new MockFilterChain();
        assertEquals(200, answer(local, allowed));
    }

    private int answer(MockHttpServletRequest request, MockFilterChain chain) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response.getStatus();
    }
}
