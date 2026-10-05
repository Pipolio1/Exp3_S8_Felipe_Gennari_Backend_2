package com.duoc.bank.authserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Servidor de autorizacion OAuth2 del ecosistema bancario (Banco XYZ).
 * Centraliza la emision de access tokens JWT (grant client_credentials):
 * cada microservicio es un cliente registrado y los recursos protegidos
 * validan los tokens contra el JWK expuesto por este servidor
 * (delegacion de seguridad, segun el modelo OAuth 2.0).
 */
@SpringBootApplication
public class AuthServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServerApplication.class, args);
    }
}
