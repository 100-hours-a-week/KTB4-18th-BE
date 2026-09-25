package com.muse.meomuneum.global.exception;

import org.springframework.http.HttpStatus;

public interface ErrorCode {

    String code();

    HttpStatus status();

    String message();
}
