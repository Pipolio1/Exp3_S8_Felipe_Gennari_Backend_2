package com.duoc.bank.operaciones.config;

import com.duoc.bank.common.exception.CodigoError;
import com.duoc.bank.common.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * Seguridad OAuth2 del microservicio de operaciones (Resource Server).
 * Valida los access tokens JWT emitidos por el auth-server (firma via JWK,
 * issuer y expiracion) y exige el scope que requiere cada operacion:
 * RETIRO para ejecutar retiros, REPORTES para estadisticas y CONSULTA para
 * el resto de la API. El login queda abierto porque es la puerta para
 * obtener el token. Los rechazos (401/403) salen con el formato uniforme
 * {@link ErrorResponse}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(autorizacion -> autorizacion
                        .requestMatchers("/api/operaciones/auth/**", "/actuator/**", "/error",
                                "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/operaciones/retiro")
                                .hasAuthority("SCOPE_RETIRO")
                        .requestMatchers("/api/operaciones/stats", "/api/operaciones/stats/**")
                                .hasAuthority("SCOPE_REPORTES")
                        .requestMatchers("/api/**").hasAuthority("SCOPE_CONSULTA")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(recursos -> recursos
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint((request, response, excepcion) -> {
                            log.warn("evento=token_rechazado metodo={} uri={}",
                                    request.getMethod(), request.getRequestURI());
                            responderError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                    CodigoError.TOKEN_INVALIDO, "Token invalido, ausente o expirado");
                        })
                        .accessDeniedHandler((request, response, excepcion) -> {
                            log.warn("evento=permiso_denegado uri={}", request.getRequestURI());
                            responderError(response, HttpServletResponse.SC_FORBIDDEN,
                                    CodigoError.ACCESO_DENEGADO, "El usuario no tiene permiso para esta operacion");
                        }));
        return http.build();
    }

    private void responderError(HttpServletResponse response, int estado, CodigoError codigo, String mensaje)
            throws java.io.IOException {
        response.setStatus(estado);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(codigo, mensaje)));
    }
}
