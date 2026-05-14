package com.vn.security.cookie;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.cookie")
public class CookieProperties {

    private String refreshTokenName = "refreshToken";
    private String refreshTokenPath = "/api/auth/";
    private boolean refreshTokenSecure = false;
    private String refreshTokenSameSite = "Lax";

    public String getRefreshTokenName() {
        return refreshTokenName;
    }

    public void setRefreshTokenName(String refreshTokenName) {
        this.refreshTokenName = refreshTokenName;
    }

    public String getRefreshTokenPath() {
        return refreshTokenPath;
    }

    public void setRefreshTokenPath(String refreshTokenPath) {
        this.refreshTokenPath = refreshTokenPath;
    }

    public boolean isRefreshTokenSecure() {
        return refreshTokenSecure;
    }

    public void setRefreshTokenSecure(boolean refreshTokenSecure) {
        this.refreshTokenSecure = refreshTokenSecure;
    }

    public String getRefreshTokenSameSite() {
        return refreshTokenSameSite;
    }

    public void setRefreshTokenSameSite(String refreshTokenSameSite) {
        this.refreshTokenSameSite = refreshTokenSameSite;
    }
}

