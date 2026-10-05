package com.duoc.bank.transacciones.config;

import com.duoc.bank.common.exception.CodigoError;
import com.duoc.bank.common.exception.CredencialesInvalidasException;
import com.duoc.bank.common.exception.ErrorResponse;
import com.duoc.bank.common.exception.ServicioNoDisponibleException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manejo centralizado de excepciones del microservicio de transacciones.
 * Toda respuesta de error usa el formato uniforme definido por
 * {@link ErrorResponse}, con su {@link CodigoError} estable, en vez de
 * Map.of dispersos en el controlador.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Parametros de consulta que no pasan la validacion (@Min, @Max, etc.) -> 400. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> parametrosInvalidos(ConstraintViolationException e) {
        Map<String, String> detalles = new LinkedHashMap<>();
        for (ConstraintViolation<?> violacion : e.getConstraintViolations()) {
            detalles.put(violacion.getPropertyPath().toString(), violacion.getMessage());
        }
        log.warn("evento=parametros_invalidos detalles={}", detalles);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CodigoError.PARAMETROS_INVALIDOS,
                        "Parametros de consulta invalidos", detalles));
    }

    /** Body que no pasa Bean Validation (login) -> 400 con el mensaje de la primera violacion. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validacionFallida(MethodArgumentNotValidException e) {
        String mensaje = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(FieldError::getDefaultMessage)
                .orElse("Solicitud invalida");
        log.warn("evento=validacion_fallida detalle={}", mensaje);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CodigoError.VALIDACION_FALLIDA, mensaje));
    }

    /** Circuito abierto: Resilience4j rechaza la llamada sin llegar al fallback -> 503. */
    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ErrorResponse> circuitoAbierto(CallNotPermittedException e) {
        log.warn("evento=circuito_abierto circuitBreaker={}", e.getCausingCircuitBreakerName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(CodigoError.SERVICIO_NO_DISPONIBLE,
                        "Servicio temporalmente no disponible; reintente mas tarde"));
    }

    /** Credenciales de login incorrectas -> 401. */
    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<ErrorResponse> credencialesInvalidas(CredencialesInvalidasException e) {
        log.warn("evento=credenciales_invalidas");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(CodigoError.CREDENCIALES_INVALIDAS, e.getMessage()));
    }

    /** Fallback de un Circuit Breaker (persistencia caida) -> 503. */
    @ExceptionHandler(ServicioNoDisponibleException.class)
    public ResponseEntity<ErrorResponse> servicioNoDisponible(ServicioNoDisponibleException e) {
        log.warn("evento=servicio_no_disponible detalle={}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(CodigoError.SERVICIO_NO_DISPONIBLE, e.getMessage()));
    }

    /** Ruta inexistente -> 404. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> recursoNoEncontrado(NoResourceFoundException e) {
        log.warn("evento=recurso_no_encontrado ruta={}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(CodigoError.RECURSO_NO_ENCONTRADO,
                        "El recurso solicitado no existe"));
    }

    /** Metodo HTTP no soportado por el endpoint -> 405. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> metodoNoPermitido(HttpRequestMethodNotSupportedException e) {
        log.warn("evento=metodo_no_permitido metodo={}", e.getMethod());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse(CodigoError.METODO_NO_PERMITIDO,
                        "Metodo HTTP no permitido para este recurso"));
    }

    /** JSON malformado o ilegible en el body -> 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> cuerpoIlegible(HttpMessageNotReadableException e) {
        log.warn("evento=solicitud_mal_formada tipo={}", e.getClass().getSimpleName());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CodigoError.SOLICITUD_MAL_FORMADA,
                        "El cuerpo de la solicitud no es un JSON valido"));
    }

    /** Parametro de ruta o consulta con tipo incorrecto -> 400. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> tipoIncorrecto(MethodArgumentTypeMismatchException e) {
        log.warn("evento=tipo_incorrecto parametro={}", e.getName());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CodigoError.SOLICITUD_MAL_FORMADA,
                        "El parametro '" + e.getName() + "' tiene un formato invalido"));
    }

    /** Parametro de consulta obligatorio ausente -> 400. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> parametroFaltante(MissingServletRequestParameterException e) {
        log.warn("evento=parametro_faltante parametro={}", e.getParameterName());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(CodigoError.PARAMETROS_INVALIDOS,
                        "Falta el parametro obligatorio '" + e.getParameterName() + "'"));
    }

    /** Cualquier otro error no controlado -> 500 generico (sin filtrar detalles internos). */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> errorInterno(Exception e) {
        log.error("evento=error_interno", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(CodigoError.ERROR_INTERNO, "Error interno del servidor"));
    }
}
