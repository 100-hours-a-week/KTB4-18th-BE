package com.muse.meomuneum.global.response;

import org.springframework.http.HttpStatus;

public interface SuccessCode {

    String code();

    HttpStatus status();

    String message();
}
