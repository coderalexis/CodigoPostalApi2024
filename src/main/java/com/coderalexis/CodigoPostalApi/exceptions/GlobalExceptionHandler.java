package com.coderalexis.CodigoPostalApi.exceptions;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ZipCodeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleZipCodeNotFoundException(ZipCodeNotFoundException ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("ZipCodeNotFoundException: {}", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    // En todos los handlers, `message` describe el error y `path` contiene la URI
    // del request (via request.getDescription); antes los handlers de validación
    // invertían esa semántica poniendo la lista de errores en `path`.

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolationException(ConstraintViolationException ex, WebRequest request) {
        String errors = ex.getConstraintViolations()
                          .stream()
                          .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                          .collect(Collectors.joining(", "));

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Errores de validación: " + errors,
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("ConstraintViolationException: {}", errors);
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex, WebRequest request) {
        String errors = ex.getBindingResult()
                          .getFieldErrors()
                          .stream()
                          .map(FieldError::getDefaultMessage)
                          .collect(Collectors.joining(", "));

        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Errores de validación: " + errors,
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("MethodArgumentNotValidException: {}", errors);
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingServletRequestParameterException(
            MissingServletRequestParameterException ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Falta el parámetro requerido: " + ex.getParameterName(),
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("MissingServletRequestParameterException: {}", ex.getParameterName());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(
            MethodArgumentTypeMismatchException ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Valor inválido para el parámetro '" + ex.getName() + "': " + ex.getValue(),
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("MethodArgumentTypeMismatchException: {}", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleHttpRequestMethodNotSupportedException(
            HttpRequestMethodNotSupportedException ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Método " + ex.getMethod() + " no soportado para este recurso",
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("HttpRequestMethodNotSupportedException: {}", ex.getMethod());

        HttpHeaders headers = new HttpHeaders();
        if (ex.getSupportedHttpMethods() != null) {
            headers.setAllow(ex.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(error, headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFoundException(NoResourceFoundException ex, WebRequest request) {
        // Ignorar peticiones de recursos estáticos del navegador (favicon, devtools, etc.)
        String path = ex.getResourcePath();
        if (path.contains("favicon") || path.contains(".well-known") || path.contains("devtools")) {
            log.debug("Recurso estático no encontrado (ignorado): {}", path);
            return ResponseEntity.notFound().build();
        }

        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Recurso no encontrado",
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("NoResourceFoundException: {}", path);
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, WebRequest request) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Ocurrió un error inesperado",
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.error("Exception: ", ex);
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(
            IllegalArgumentException ex,
            WebRequest request
    ) {
        ErrorResponse error = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Argumento inválido: " + ex.getMessage(),
                request.getDescription(false),
                LocalDateTime.now()
        );
        log.warn("IllegalArgumentException: {}", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }
}
