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
    public static final String WEBHOOK_HEADER = "apikey";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_WEBHOOK = "WEBHOOK";
    static final int MAX_WEBHOOK_BODY_BYTES = 1024 * 1024;

    private final String adminToken;
    private final String evolutionKey;
    private final InstanceTokenValidator instanceTokenValidator;
    private final JsonMapper jsonMapper;
    private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(String adminToken,
                                      String evolutionKey,
                                      InstanceTokenValidator instanceTokenValidator,
                                      JsonMapper jsonMapper) {
        this.adminToken = adminToken;
        this.evolutionKey = evolutionKey;
        this.instanceTokenValidator = instanceTokenValidator;
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
                System.out.printf("[WEBHOOK-AUTH] Rejected | path=%s | reason=body_too_large | status=413%n", path);
                response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
                return;
            }
            String apiKey;
            boolean valid;
            if (path.equals("/webhook")) {
                apiKey = request.getHeader(WEBHOOK_HEADER);
                valid = tokenMatches(apiKey, evolutionKey);
            } else {
                JsonNode body = readJson(cached.body());
                apiKey = text(body, "apikey");
                valid = tokenMatches(apiKey, evolutionKey)
                        || instanceTokenValidator.isValid(text(body, "instance"), apiKey);
            }
            if (valid) {
                authenticate("webhook", ROLE_WEBHOOK);
            } else {
                String reason = apiKey == null || apiKey.isBlank() ? "missing_apikey" : "apikey_mismatch";
                System.out.printf("[WEBHOOK-AUTH] Rejected | path=%s | reason=%s | body_bytes=%d | status=401%n",
                        path, reason, cached.body().length);
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

    private JsonNode readJson(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            return jsonMapper.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.asString() : null;
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
