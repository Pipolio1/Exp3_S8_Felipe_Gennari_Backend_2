package com.duoc.bank.operaciones.controller;

import com.duoc.bank.common.exception.CredencialesInvalidasException;
import com.duoc.bank.common.exception.ErrorResponse;
import com.duoc.bank.operaciones.dto.LoginRequest;
import com.duoc.bank.operaciones.dto.LoginResponse;
import com.duoc.bank.operaciones.security.AuthService;
import com.duoc.bank.operaciones.security.OAuth2TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Autenticacion del microservicio de operaciones: valida las credenciales del
 * usuario y delega la emision del access token en el auth-server (OAuth2,
 * grant client_credentials), pidiendo como scopes los permisos del usuario.
 * Sigue protegido por el API key (primera barrera) y queda abierto en la
 * configuracion de seguridad para poder obtener el token.
 */
@RestController
@RequestMapping("/api/operaciones/auth")
@Tag(name = "Autenticacion", description = "Emision del access token OAuth2 del microservicio de operaciones")
public class AuthController {

    private final AuthService authService;
    private final OAuth2TokenService tokenService;

    public AuthController(AuthService authService, OAuth2TokenService tokenService) {
        this.authService = authService;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    @Operation(summary = "Autentica al usuario del servicio y entrega su access token OAuth2")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Autenticacion exitosa; devuelve el access token"),
            @ApiResponse(responseCode = "400", description = "Cuerpo de la solicitud invalido o incompleto",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "API key invalida o credenciales incorrectas",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        List<String> permisos = authService.autenticar(request.getUsuario(), request.getClave());
        if (permisos == null) {
            throw new CredencialesInvalidasException("Credenciales invalidas");
        }
        OAuth2TokenService.TokenOAuth2 token = tokenService.solicitarToken(permisos);
        return ResponseEntity.ok(new LoginResponse(token.valor(), "Bearer", token.expiraEnMinutos()));
    }
}
