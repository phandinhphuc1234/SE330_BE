package com.vn.auth.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CookieAuthCsrfProtectionTest {
    private CsrfFilter filter() {
        CsrfFilter filter = new CsrfFilter(new CookieCsrfTokenRepository());
        filter.setRequireCsrfProtectionMatcher(SecurityConfig::requiresCookieAuthCsrf);
        filter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        return filter;
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    @Test
    void refreshWithoutToken_shouldBeRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain downstream = mock(FilterChain.class);
        filter().doFilter(request("POST", "/api/auth/refresh"), response, downstream);
        assertThat(response.getStatus()).isEqualTo(403);
        verify(downstream, never()).doFilter(any(), any());
    }

    @Test
    void matchingCookieAndHeader_shouldAllowRefreshWithoutServerSession() throws Exception {
        MockHttpServletRequest bootstrap = request("GET", "/api/auth/csrf");
        MockHttpServletResponse tokenResponse = new MockHttpServletResponse();
        String[] token = new String[2];
        filter().doFilter(bootstrap, tokenResponse, (req, res) -> {
            CsrfToken csrf = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            token[0] = csrf.getToken();
            token[1] = csrf.getHeaderName();
        });
        assertThat(tokenResponse.getCookie("XSRF-TOKEN")).isNotNull();
        assertThat(tokenResponse.getCookie("XSRF-TOKEN").isHttpOnly()).isTrue();

        MockHttpServletRequest refresh = request("POST", "/api/auth/refresh");
        refresh.setCookies(tokenResponse.getCookie("XSRF-TOKEN"));
        refresh.addHeader(token[1], token[0]);
        FilterChain downstream = mock(FilterChain.class);
        filter().doFilter(refresh, new MockHttpServletResponse(), downstream);
        verify(downstream).doFilter(any(), any());
        assertThat(refresh.getSession(false)).isNull();

        MockHttpServletRequest crossSiteForm = request("POST", "/api/auth/refresh");
        crossSiteForm.setCookies(tokenResponse.getCookie("XSRF-TOKEN"));
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter().doFilter(crossSiteForm, rejected, mock(FilterChain.class));
        assertThat(rejected.getStatus()).isEqualTo(403);
    }

    @Test
    void bearerAndSignedWebhookApis_shouldNotRequireCookieCsrf() {
        assertThat(SecurityConfig.requiresCookieAuthCsrf(request("POST", "/api/ebooks/1/ask"))).isFalse();
        assertThat(SecurityConfig.requiresCookieAuthCsrf(request("POST", "/api/webhooks/resend"))).isFalse();
        assertThat(SecurityConfig.requiresCookieAuthCsrf(request("GET", "/api/books"))).isFalse();
    }
}
