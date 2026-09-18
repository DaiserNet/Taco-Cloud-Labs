package tacos.api.error;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.validation.ConstraintViolationException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import tacos.web.api.EmailOrderConversionException;

@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiProblem> handleValidation(
      MethodArgumentNotValidException error, HttpServletRequest request) {
    List<ApiProblem.Violation> violations = error.getBindingResult().getFieldErrors().stream()
        .map(this::toViolation)
        .sorted(Comparator.comparing(ApiProblem.Violation::getField)
            .thenComparing(ApiProblem.Violation::getMessage))
        .collect(Collectors.toList());
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
        "One or more request fields are invalid.", "VALIDATION_FAILED", violations, request);
  }

  @ExceptionHandler(BindException.class)
  public ResponseEntity<ApiProblem> handleBind(
      BindException error, HttpServletRequest request) {
    List<ApiProblem.Violation> violations = error.getFieldErrors().stream()
        .map(this::toViolation)
        .sorted(Comparator.comparing(ApiProblem.Violation::getField)
            .thenComparing(ApiProblem.Violation::getMessage))
        .collect(Collectors.toList());
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
        "One or more request fields are invalid.", "VALIDATION_FAILED", violations, request);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiProblem> handleConstraintViolation(
      ConstraintViolationException error, HttpServletRequest request) {
    List<ApiProblem.Violation> violations = error.getConstraintViolations().stream()
        .map(violation -> new ApiProblem.Violation(
            violation.getPropertyPath().toString(), violation.getMessage()))
        .sorted(Comparator.comparing(ApiProblem.Violation::getField)
            .thenComparing(ApiProblem.Violation::getMessage))
        .collect(Collectors.toList());
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed",
        "One or more request fields are invalid.", "VALIDATION_FAILED", violations, request);
  }

  @ExceptionHandler({
      HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class,
      MissingServletRequestParameterException.class
  })
  public ResponseEntity<ApiProblem> handleMalformed(
      Exception error, HttpServletRequest request) {
    return problem(HttpStatus.BAD_REQUEST, "Malformed request",
        "The request body or parameters are missing or malformed.",
        "MALFORMED_REQUEST", Collections.emptyList(), request);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiProblem> handleResponseStatus(
      ResponseStatusException error, HttpServletRequest request) {
    HttpStatus status = error.getStatus();
    switch (status) {
      case BAD_REQUEST:
        return problem(status, "Bad request", "The request cannot be processed.",
            "MALFORMED_REQUEST", Collections.emptyList(), request);
      case UNAUTHORIZED:
        return problem(status, "Unauthorized", "Authentication is required.",
            "AUTHENTICATION_REQUIRED", Collections.emptyList(), request);
      case FORBIDDEN:
        return problem(status, "Forbidden", "Access to the resource is forbidden.",
            "ACCESS_DENIED", Collections.emptyList(), request);
      case NOT_FOUND:
        return problem(status, "Resource not found", "The requested resource was not found.",
            "RESOURCE_NOT_FOUND", Collections.emptyList(), request);
      case CONFLICT:
        return problem(status, "Resource conflict",
            "The resource conflicts with its current state.",
            "ORDER_STATE_CONFLICT", Collections.emptyList(), request);
      case UNPROCESSABLE_ENTITY:
        return problem(status, "Validation failed",
            "One or more request fields are invalid.",
            "VALIDATION_FAILED", Collections.emptyList(), request);
      default:
        return problem(status, status.getReasonPhrase(),
            "The request could not be completed.",
            "HTTP_ERROR", Collections.emptyList(), request);
    }
  }

  @ExceptionHandler(EmailOrderConversionException.class)
  public ResponseEntity<ApiProblem> handleBusinessRule(
      EmailOrderConversionException error, HttpServletRequest request) {
    return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Business rule violation",
        error.getMessage(), error.getCode(), Collections.emptyList(), request);
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ApiProblem> handleDataAccess(
      DataAccessException error, HttpServletRequest request) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
        "An internal data access error occurred.", "DATA_ACCESS_ERROR",
        Collections.emptyList(), request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiProblem> handleUnexpected(
      Exception error, HttpServletRequest request) {
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error",
        "An unexpected internal error occurred.", "INTERNAL_ERROR",
        Collections.emptyList(), request);
  }

  private ApiProblem.Violation toViolation(FieldError error) {
    return new ApiProblem.Violation(error.getField(), error.getDefaultMessage());
  }

  private ResponseEntity<ApiProblem> problem(HttpStatus status, String title, String detail,
      String code, List<ApiProblem.Violation> violations, HttpServletRequest request) {
    String type = "urn:tacocloud:problem:"
        + code.toLowerCase(Locale.ROOT).replace('_', '-');
    ApiProblem body = new ApiProblem(type, title, status.value(), detail,
        request.getRequestURI(), code, violations);
    return ResponseEntity.status(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(body);
  }
}
