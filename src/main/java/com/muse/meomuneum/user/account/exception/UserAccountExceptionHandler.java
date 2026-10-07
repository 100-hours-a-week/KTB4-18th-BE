package com.muse.meomuneum.user.account.exception;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.handler.AbstractHandlerExceptionResolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.meomuneum.global.response.ApiResponse;
import com.muse.meomuneum.user.account.controller.UserAccountController;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = UserAccountController.class)
public class UserAccountExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UserAccountExceptionHandler.class);

    @Bean
    public HandlerExceptionResolver profileImageUploadSizeResolver(ObjectProvider<ObjectMapper> objectMapper) {
        return new ProfileImageUploadSizeResolver(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    private static final class ProfileImageUploadSizeResolver extends AbstractHandlerExceptionResolver {

        private final ObjectMapper objectMapper;

        private ProfileImageUploadSizeResolver(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            setOrder(Ordered.HIGHEST_PRECEDENCE);
        }

        @Override
        protected ModelAndView doResolveException(HttpServletRequest request, HttpServletResponse response,
                Object handler, Exception exception) {
            if (!(exception instanceof MaxUploadSizeExceededException) || !"PUT".equals(request.getMethod()) ||
                    !(request.getContextPath() + "/api/v1/users/me/profile-image").equals(request.getRequestURI())) {
                return null;
            }
            response.setStatus(413);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            try {
                objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure("image is too large"));
            } catch (IOException writeFailure) {
                log.warn("event=user_profile_image_size_response_failed");
            }
            return new ModelAndView();
        }
    }

    @ExceptionHandler(UserAccountException.class)
    public ResponseEntity<ApiResponse<Void>> handle(UserAccountException exception, HttpServletRequest request) {
        log.warn("event=user_account_request_failed domainCode={} httpStatus={} method={} path={} requestId={}",
                exception.getCode(), exception.getStatus().value(), request.getMethod(), request.getRequestURI(),
                MDC.get("requestId"));
        return ResponseEntity.status(exception.getStatus()).body(ApiResponse.failure(exception.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> imageTooLarge() {
        return ResponseEntity.status(413).body(ApiResponse.failure("image is too large"));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> invalidRequest(Exception exception, HttpServletRequest request) {
        log.warn("event=user_account_invalid_request httpStatus=400 method={} path={} requestId={} exceptionType={}",
                request.getMethod(), request.getRequestURI(), MDC.get("requestId"),
                exception.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(ApiResponse.failure("invalid request"));
    }
}
