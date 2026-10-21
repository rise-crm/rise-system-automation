package com.dariodussin.whatsappautomationbackend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String ADMIN_HEADER = "X-Admin-Token";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_WEBHOOK = "WEBHOOK";
    static final int MAX_WEBHOOK_BODY_BYTES = 1024 * 1024;

    private final String adminToken;
    private final String webhookToken;
    private final JsonMapper jsonMapper;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(String adminToken, String webhookToken, JsonMapper jsonMapper) {
        this.adminToken = adminToken;
        this.webhookToken = webhookToken;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());

        if (isUnder(path, "/admin")) {
            if (tokenMatches(request.getHeader(ADMIN_HEADER), adminToken)) {
                authenticate("admin", ROLE_ADMIN);
            }
            chain.doFilter(request, response);
            return;
        }

        if (isUnder(path, "/webhook")) {
            CachedBodyHttpServletRequest cached = CachedBodyHttpServletRequest.wrap(request, MAX_WEBHOOK_BODY_BYTES);
            if (cached == null) {
                response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
                return;
            }
            if (tokenMatches(apiKeyFrom(cached.body()), webhookToken)) {
                authenticate("webhook", ROLE_WEBHOOK);
            }
            chain.doFilter(cached, response);
            return;
        }

        chain.doFilter(request, response);
    }

    private void authenticate(String principal, String role) {
        SecurityContext context = contextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
        contextHolder.setContext(context);
    }

    private String apiKeyFrom(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode apikey = jsonMapper.readTree(body).get("apikey");
            return apikey != null && apikey.isString() ? apikey.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static boolean isUnder(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    static boolean tokenMatches(String provided, String expected) {
        if (provided == null || expected == null || expected.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(sha256(provided), sha256(expected));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
