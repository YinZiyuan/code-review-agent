package dev.langchain4j.example.codereview.server;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GitHubWebhookController.class)
public final class WebhookExceptionHandler {

    @ExceptionHandler(WebhookRequestException.class)
    ResponseEntity<String> handleWebhookRequest(WebhookRequestException exception) {
        return fixedCode(exception.status(), exception.code());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<String> handleUnreadableBody(HttpMessageNotReadableException exception) {
        if (causedByPayloadOverflow(exception)) {
            return fixedCode(HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_PAYLOAD_TOO_LARGE");
        }
        return fixedCode(HttpStatus.BAD_REQUEST, "MALFORMED_WEBHOOK_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<String> handleUnexpectedFailure(Exception exception) {
        return fixedCode(HttpStatus.INTERNAL_SERVER_ERROR, "WEBHOOK_PROCESSING_FAILED");
    }

    private static ResponseEntity<String> fixedCode(HttpStatus status, String code) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_PLAIN)
                .body(code);
    }

    private static boolean causedByPayloadOverflow(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof WebhookPayloadTooLargeException) {
                return true;
            }
        }
        return false;
    }
}

final class WebhookRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private WebhookRequestException(HttpStatus status, String code) {
        super(code, null, false, false);
        this.status = status;
        this.code = code;
    }

    static WebhookRequestException malformedRequest() {
        return new WebhookRequestException(HttpStatus.BAD_REQUEST, "MALFORMED_WEBHOOK_REQUEST");
    }

    static WebhookRequestException invalidSignature() {
        return new WebhookRequestException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE");
    }

    static WebhookRequestException invalidPayload() {
        return new WebhookRequestException(HttpStatus.BAD_REQUEST, "INVALID_PULL_REQUEST_PAYLOAD");
    }

    static WebhookRequestException payloadTooLarge() {
        return new WebhookRequestException(HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_PAYLOAD_TOO_LARGE");
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }
}
