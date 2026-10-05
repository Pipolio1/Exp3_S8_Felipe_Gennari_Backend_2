package com.duoc.bank.transacciones.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra las barreras transversales del microservicio, en orden de ejecucion
 * (despues del filtro de Spring Security, que valida el JWT):
 * 1) {@link ApiKeyInterceptor} para todos los endpoints (excluye {@code /error}
 *    y la documentacion OpenAPI/Swagger).
 * 2) {@link RateLimitInterceptor} para la API: tope de solicitudes por cliente.
 * La autorizacion por scopes la resuelve {@link SecurityConfig} (OAuth2
 * Resource Server) antes de que la solicitud llegue a estos interceptores.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiKeyInterceptor apiKeyInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(ApiKeyInterceptor apiKeyInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.apiKeyInterceptor = apiKeyInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiKeyInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error", "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
    }
}
