package com.duoc.bank.common.exception;

/**
 * Las credenciales entregadas en el login no corresponden a ningun usuario
 * configurado del servicio. Los controladores de autenticacion la lanzan y el
 * {@code GlobalExceptionHandler} de cada servicio la traduce a 401 con el
 * codigo estable {@link CodigoError#CREDENCIALES_INVALIDAS}, manteniendo el
 * contrato uniforme de errores en vez de construir la respuesta a mano.
 */
public class CredencialesInvalidasException extends RuntimeException {

    public CredencialesInvalidasException(String mensaje) {
        super(mensaje);
    }
}
