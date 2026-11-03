package com.muse.meomuneum.global.security;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import com.muse.meomuneum.auth.directv2.repository.DirectAuthProjectionRepository;

public class DirectAuthAdmissionFilter extends OncePerRequestFilter {
    private final DirectAuthAdmissionFlag flag;
    private final SecurityErrorResponseWriter writer;
    private final DirectAuthProjectionRepository projections;
    private final boolean refreshVerified;
    private final boolean recoveryVerified;
    private final boolean rebootstrapVerified;

    public DirectAuthAdmissionFilter(DirectAuthAdmissionFlag flag, SecurityErrorResponseWriter writer) {
        this(flag, writer, null);
    }

    public DirectAuthAdmissionFilter(DirectAuthAdmissionFlag flag, SecurityErrorResponseWriter writer,
            DirectAuthProjectionRepository projections) {
        this(flag, writer, projections, false);
    }

    public DirectAuthAdmissionFilter(DirectAuthAdmissionFlag flag, SecurityErrorResponseWriter writer,
            DirectAuthProjectionRepository projections, boolean refreshVerified) {
        this(flag, writer, projections, refreshVerified, false);
    }

    public DirectAuthAdmissionFilter(DirectAuthAdmissionFlag flag, SecurityErrorResponseWriter writer,
            DirectAuthProjectionRepository projections, boolean refreshVerified, boolean recoveryVerified) {
        this(flag, writer, projections, refreshVerified, recoveryVerified, false);
    }

    public DirectAuthAdmissionFilter(DirectAuthAdmissionFlag flag, SecurityErrorResponseWriter writer,
            DirectAuthProjectionRepository projections, boolean refreshVerified, boolean recoveryVerified,
            boolean rebootstrapVerified) {
        this.flag = flag;
        this.writer = writer;
        this.projections = projections;
        this.refreshVerified = refreshVerified;
        this.recoveryVerified = recoveryVerified;
        this.rebootstrapVerified = rebootstrapVerified;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = AuthRequestPath.path(request);
        boolean direct = "direct-v2".equals(request.getHeader("X-Auth-Login-Protocol"));
        boolean post = "POST".equals(request.getMethod());
        if (post && direct && !flag.enabled() && (path.equals("/api/v1/auth/login")
                || path.equals("/api/v1/auth/flows"))) {
            writer.write(request, response, SecurityErrorCode.LOGIN_PROTOCOL_DISABLED);
            return;
        }
        if (post && direct && (path.equals("/api/v1/auth/token/refresh") && (!flag.enabled() || !refreshVerified)
                || path.equals("/api/v1/auth/flow/rebootstrap") && (!flag.enabled() || !rebootstrapVerified)
                || path.matches("/api/v1/auth/token/refresh/operations/[^/]+/session-recovery") && !recoveryVerified)) {
            writer.write(request, response, SecurityErrorCode.AUTH_STATE_UNAVAILABLE);
            return;
        }
        boolean sensitive = post && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout")
                || path.equals("/api/v1/auth/login/recovery") || path.equals("/api/v1/auth/token/refresh")
                || path.equals("/api/v1/auth/flows")
                || path.matches("/api/v1/auth/login/operations/[^/]+/session-recovery")
                || path.matches("/api/v1/auth/token/refresh/operations/[^/]+/session-recovery"));
        if (sensitive && projections != null && (direct || !path.equals("/api/v1/auth/login"))) {
            try {
                boolean generation = projections.generationMode();
                if (direct != generation) {
                    writer.write(request, response, SecurityErrorCode.AUTH_STATE_UNAVAILABLE);
                    return;
                }
            } catch (RuntimeException unavailable) {
                writer.write(request, response, SecurityErrorCode.AUTH_STATE_UNAVAILABLE);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
