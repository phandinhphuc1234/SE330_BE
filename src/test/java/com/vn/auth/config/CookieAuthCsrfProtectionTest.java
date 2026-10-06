package com.vn.auth.config;

import com.vn.auth.security.cookie.CookieProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CookieAuthCsrfProtectionTest {
    private CsrfFilter filter() {
        return filter(new CookieProperties());
    }

    private CsrfFilter filter(CookieProperties properties) {
        CsrfFilter filter = new CsrfFilter(SecurityConfig.cookieCsrfTokenRepository(properties));
        filter.setRequireCsrfProtectionMatcher(SecurityConfig::requiresCookieAuthCsrf);
        filter.setRequestHandler(new XorCsrfTokenRequestAttributeHandler());
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
        assertThat(token[0]).isNotEqualTo(tokenResponse.getCookie("XSRF-TOKEN").getValue());

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

        MockHttpServletRequest incorrectHeader = request("POST", "/api/auth/refresh");
        incorrectHeader.setCookies(tokenResponse.getCookie("XSRF-TOKEN"));
        incorrectHeader.addHeader(token[1], "incorrect-token");
        MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
        FilterChain rejectedDownstream = mock(FilterChain.class);
        filter().doFilter(incorrectHeader, invalidResponse, rejectedDownstream);
        assertThat(invalidResponse.getStatus()).isEqualTo(403);
        verify(rejectedDownstream, never()).doFilter(any(), any());
    }

    @Test
    void productionCsrfCookie_shouldBeSecureHttpOnlyAndHostBound() throws Exception {
        CookieProperties properties = new CookieProperties();
        properties.setRefreshTokenSecure(true);
        properties.setRefreshTokenSameSite("None");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(properties).doFilter(request("GET", "/api/auth/csrf"), response, (req, res) -> {
            CsrfToken csrf = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
            csrf.getToken();
        });

        var cookie = response.getCookie("__Host-XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("None");
    }

    @Test
    void bearerAndSignedWebhookApis_shouldNotRequireCookieCsrf() {
        assertThat(SecurityConfig.requiresCookieAuthCsrf(request("POST", "/api/ebooks/1/ask"))).isFalse();
        assertThat(SecurityConfig.requiresCookieAuthCsrf(request("POST", "/api/webhooks/resend"))).isFalse();
        assertThat(SecurityConfig.requiresCookieAuthCsrf(request("GET", "/api/books"))).isFalse();
    }
}
