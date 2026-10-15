package com.muse.meomuneum.auth.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

@Service
public class CsrfTokenService {

    private final CsrfTokenRepository csrfTokenRepository;

    public CsrfTokenService(CsrfTokenRepository csrfTokenRepository) {
        this.csrfTokenRepository = csrfTokenRepository;
    }

    public String issueToken(HttpServletRequest request, HttpServletResponse response) {
        CsrfToken token = csrfTokenRepository.loadToken(request);
        if (token == null) {
            token = csrfTokenRepository.generateToken(request);
            if (response != null) {
                csrfTokenRepository.saveToken(token, request, response);
            }
        }

        return token.getToken();
    }

    public String issueToken(HttpServletRequest request) {
        return issueToken(request, null);
    }
}
