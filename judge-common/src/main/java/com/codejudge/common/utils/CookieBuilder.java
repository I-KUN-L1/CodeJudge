package com.codejudge.common.utils;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cookie 构建工具
 */
public class CookieBuilder {

    private final String name;
    private String value;
    private String domain;
    private String path = "/";
    private int maxAge = -1;
    private boolean httpOnly = true;
    private boolean secure = false;
    private String sameSite;

    private CookieBuilder(String name) {
        this.name = name;
    }

    public static CookieBuilder newBuilder(String name) {
        return new CookieBuilder(name);
    }

    public CookieBuilder value(String value) {
        this.value = value;
        return this;
    }

    public CookieBuilder domain(String domain) {
        this.domain = domain;
        return this;
    }

    public CookieBuilder path(String path) {
        this.path = path;
        return this;
    }

    public CookieBuilder maxAge(int maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    public CookieBuilder httpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    public CookieBuilder secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    public CookieBuilder sameSite(String sameSite) {
        this.sameSite = sameSite;
        return this;
    }

    public Cookie build() {
        Cookie cookie = new Cookie(name, value);
        cookie.setDomain(domain);
        cookie.setPath(path);
        cookie.setMaxAge(maxAge);
        cookie.setHttpOnly(httpOnly);
        cookie.setSecure(secure);
        return cookie;
    }

    public void write(HttpServletResponse response) {
        // SameSite 不是 Servlet Cookie 的标准属性，必须手拼 Set-Cookie 头。
        // 历史实现有两个缺陷：① 格式串里没有 Secure/Domain 的位置，调 .secure(true)/.domain(...)
        // 会被静默丢弃；② setHeader 会覆盖整个 Set-Cookie 头，多枚 cookie 时先写的被吞。
        // 现统一 addHeader 并按需拼接全部属性。
        if (StringUtils.isNotBlank(sameSite)) {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append('=').append(value == null ? "" : value)
              .append("; Path=").append(path)
              .append("; Max-Age=").append(maxAge);
            if (StringUtils.isNotBlank(domain)) {
                sb.append("; Domain=").append(domain);
            }
            if (httpOnly) {
                sb.append("; HttpOnly");
            }
            if (secure) {
                sb.append("; Secure");
            }
            sb.append("; SameSite=").append(sameSite);
            response.addHeader("Set-Cookie", sb.toString());
        } else {
            response.addCookie(build());
        }
    }
}
