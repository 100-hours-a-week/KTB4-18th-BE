package com.muse.meomuneum.recommendation.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.muse.meomuneum.recommendation.controller.SpeechTranscriptionController;

@RestControllerAdvice(assignableTypes = SpeechTranscriptionController.class)
public class SpeechTranscriptionExceptionHandler {
    @ExceptionHandler(SpeechTranscriptionException.class)
    public ResponseEntity<SpeechTranscriptionController.ApiResponse<Void>> handle(
            SpeechTranscriptionException exception) {
        return ResponseEntity.status(exception.getStatus())
                .body(new SpeechTranscriptionController.ApiResponse<>(exception.getMessage(), null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<SpeechTranscriptionController.ApiResponse<Void>> tooLarge(
            MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413)
                .body(new SpeechTranscriptionController.ApiResponse<>(
                        "음성 파일은 최대 10MB까지 전송할 수 있습니다.", null));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    public ResponseEntity<SpeechTranscriptionController.ApiResponse<Void>> missingAudio(
            Exception exception) {
        return ResponseEntity.badRequest()
                .body(new SpeechTranscriptionController.ApiResponse<>("음성 파일을 선택해 주세요.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<SpeechTranscriptionController.ApiResponse<Void>> unexpected(Exception exception) {
        return ResponseEntity.internalServerError()
                .body(new SpeechTranscriptionController.ApiResponse<>(
                        "음성을 변환하지 못했습니다. 잠시 후 다시 시도해 주세요.", null));
    }
}
