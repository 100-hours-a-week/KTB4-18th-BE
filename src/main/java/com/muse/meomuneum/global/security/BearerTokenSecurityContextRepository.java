package com.muse.meomuneum.global.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

public class BearerTokenSecurityContextRepository implements SecurityContextRepository {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecurityContextRepository sessionRepository;
    private final SecurityContextRepository requestRepository = new RequestAttributeSecurityContextRepository();

    public BearerTokenSecurityContextRepository(SecurityContextRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    public SecurityContext loadContext(HttpRequestResponseHolder requestResponseHolder) {
        return repositoryFor(requestResponseHolder.getRequest()).loadContext(requestResponseHolder);
    }

    @Override
    public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
        return repositoryFor(request).loadDeferredContext(request);
    }

    @Override
    public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
        repositoryFor(request).saveContext(context, request, response);
    }

    @Override
    public boolean containsContext(HttpServletRequest request) {
        return repositoryFor(request).containsContext(request);
    }

    private SecurityContextRepository repositoryFor(HttpServletRequest request) {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        return authorization != null && authorization.startsWith(BEARER_PREFIX)
                ? requestRepository
                : sessionRepository;
    }
}
