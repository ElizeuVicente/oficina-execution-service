package com.oficina.execution;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenFilterTest {
    private static final String TOKEN = "12345678901234567890123456789012";
    @Test void protectsApiButAllowsHealthAndValidCredential() throws Exception {
        var filter = new ServiceTokenFilter(TOKEN);
        var request = new MockHttpServletRequest("GET", "/api/branches");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        request = new MockHttpServletRequest("GET", "/api/branches");
        request.addHeader("X-Service-Token", "wrong-token");
        response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(401, response.getStatus());
        request = new MockHttpServletRequest("GET", "/api/branches");
        request.addHeader("X-Service-Token", TOKEN);
        response = new MockHttpServletResponse();
        var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertNotNull(chain.getRequest());
        request = new MockHttpServletRequest("GET", "/actuator/health");
        chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNotNull(chain.getRequest());
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenFilter("short"));
    }
    @Test void optionalBranchAllowlistRejectsOtherAndMalformedBranches() throws Exception {
        var allowed = java.util.UUID.randomUUID(); var other = java.util.UUID.randomUUID();
        var filter = new ServiceTokenFilter(TOKEN, allowed.toString());
        for (var branch : java.util.List.of(other.toString(), "invalid")) {
            var request = new MockHttpServletRequest("GET", "/api/branches/" + branch + "/parts/test/catalog");
            request.addHeader("X-Service-Token", TOKEN); var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain()); assertEquals(403, response.getStatus());
        }
        var request = new MockHttpServletRequest("GET", "/api/branches/" + allowed + "/parts/test/catalog");
        request.addHeader("X-Service-Token", TOKEN); var chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain); assertNotNull(chain.getRequest());
        assertThrows(IllegalArgumentException.class, () -> new ServiceTokenFilter(TOKEN, "not-a-uuid"));
    }
}
