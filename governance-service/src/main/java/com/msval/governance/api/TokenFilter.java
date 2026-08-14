package com.msval.governance.api;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.msval.governance.config.MsvalProperties;
import com.msval.governance.persist.Jsonb;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * DD-016 / W5 — single static bearer (MSVAL_API_TOKEN), one filter, mutations only
 * (POST/PUT under /api/). W5 exception: POST /api/v1/decisions is unauthenticated
 * (dry-run, the endpoint table's Auth column is normative).
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    private final MsvalProperties cfg;

    public TokenFilter(MsvalProperties cfg) {
        this.cfg = cfg;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method)) {
            return true;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        return path.equals("/api/v1/decisions"); // W5: auth "—"
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String expected = "Bearer " + cfg.apiToken();
        if (cfg.apiToken() == null || !expected.equals(header)) {
            response.setStatus(401);
            response.setContentType("application/json");
            ObjectNode body = Jsonb.MAPPER.createObjectNode();
            body.put("category", "AUTH");
            body.put("detail", "missing or invalid bearer token");
            response.getWriter().write(body.toString());
            return;
        }
        chain.doFilter(request, response);
    }
}
