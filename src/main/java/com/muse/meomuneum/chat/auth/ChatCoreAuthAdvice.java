package com.muse.meomuneum.chat.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.muse.meomuneum.global.response.ApiResponse;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "chat.core-auth.enabled", havingValue = "true")
public class ChatCoreAuthAdvice implements ResponseBodyAdvice<Object> {
    @ExceptionHandler(ChatAuthUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(ChatAuthUnavailableException exception) {
        return ResponseEntity.status(503).header("Cache-Control", "no-store").header("Retry-After", "2")
                .body(ApiResponse.failure("chat authentication service unavailable"));
    }

    @Override
    public boolean supports(MethodParameter type, Class<? extends HttpMessageConverter<?>> converter) {
        return type.getContainingClass().getPackageName().startsWith("com.muse.meomuneum.chat.")
                && type.getContainingClass() != ChatCoreAuthAdvice.class;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter type, MediaType media,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (response instanceof org.springframework.http.server.ServletServerHttpResponse servlet
                && servlet.getServletResponse().getStatus() >= 400) {
            return body;
        }
        ChatAuthOperation.check();
        return body;
    }
}
