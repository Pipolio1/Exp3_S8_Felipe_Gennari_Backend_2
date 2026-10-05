package com.duoc.bank.authserver.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Configuracion del Authorization Server OAuth2 (Spring Authorization Server).
 *
 * Expone el endpoint de tokens {@code /oauth2/token} (grant client_credentials)
 * y el JWK Set {@code /oauth2/jwks} con la clave publica RSA con la que los
 * Resource Servers (los microservicios) verifican la firma de los access tokens.
 *
 * Los clientes registrados son los propios microservicios; se cargan desde la
 * propiedad {@code seguridad.clientes} con formato
 * {@code client-id:secret:SCOPE1,SCOPE2;...}. El issuer es configurable por
 * entorno para que la validacion del claim {@code iss} funcione igual en
 * ejecucion local (localhost:9000) y en Docker (auth-server:9000).
 */
@Configuration
@EnableWebSecurity
public class AuthorizationServerConfig {

    /** Cadena de filtros de los endpoints del protocolo OAuth2 (token, jwks, etc.). */
    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class).oidc(Customizer.withDefaults());
        http.exceptionHandling(excepciones -> excepciones.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /** Cadena por defecto: el resto de rutas no requiere autenticacion. */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(autorizacion -> autorizacion.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Clientes OAuth2 registrados (los microservicios del ecosistema), cargados
     * desde {@code seguridad.clientes}. Cada uno se autentica con
     * client_secret_basic y solo puede pedir tokens con los scopes declarados.
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(
            @Value("${seguridad.clientes}") String clientesConfig,
            @Value("${seguridad.token-expiracion-minutos}") long expiracionMinutos) {
        List<RegisteredClient> clientes = new ArrayList<>();
        for (String entrada : clientesConfig.split(";")) {
            String[] partes = entrada.split(":");
            if (partes.length == 3) {
                RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
                        .clientId(partes[0])
                        .clientSecret("{noop}" + partes[1])
                        .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                        .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                        .tokenSettings(TokenSettings.builder()
                                .accessTokenTimeToLive(Duration.ofMinutes(expiracionMinutos))
                                .build());
                for (String scope : partes[2].split(",")) {
                    builder.scope(scope.trim());
                }
                clientes.add(builder.build());
            }
        }
        return new InMemoryRegisteredClientRepository(clientes);
    }

    /** Par de claves RSA generado al arranque; firma los JWT y se publica via JWK. */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = generarParRsa();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /** Issuer fijo: el claim iss del token coincide en local y en Docker. */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings(
            @Value("${seguridad.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    private static KeyPair generarParRsa() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(2048);
            return generador.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el par de claves RSA", e);
        }
    }
}
