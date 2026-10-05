package com.duoc.bank.cuentas.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente OAuth2 del microservicio de cuentas: solicita access tokens al
 * Authorization Server con el flujo client_credentials (RFC 6749, seccion 4.4),
 * autenticandose con las credenciales del cliente registrado para este servicio
 * ({@code seguridad.cliente-id} / {@code seguridad.cliente-secret}).
 *
 * Los permisos del usuario autenticado en el login se solicitan como scopes del
 * token, de modo que el Resource Server puede autorizar cada operacion por
 * scope. Los tokens se cachean hasta 30 s antes de su expiracion para no
 * golpear al auth-server en cada solicitud.
 */
@Service
public class OAuth2TokenService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenService.class);
    private static final long MARGEN_EXPIRACION_SEGUNDOS = 30;

    /** Access token emitido por el auth-server y su vigencia total en minutos. */
    public record TokenOAuth2(String valor, long expiraEnMinutos) {
    }

    private record TokenRespuesta(@JsonProperty("access_token") String accessToken,
                                  @JsonProperty("expires_in") long expiresIn) {
    }

    private record TokenCacheado(String valor, long expiraEnMinutos, Instant recogerDespuesDe) {
    }

    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, TokenCacheado> cache = new ConcurrentHashMap<>();

    private final String tokenUrl;
    private final String clienteId;
    private final String clienteSecret;

    public OAuth2TokenService(@Value("${seguridad.auth-server-url}") String authServerUrl,
                              @Value("${seguridad.cliente-id}") String clienteId,
                              @Value("${seguridad.cliente-secret}") String clienteSecret) {
        this.tokenUrl = authServerUrl + "/oauth2/token";
        this.clienteId = clienteId;
        this.clienteSecret = clienteSecret;
    }

    /**
     * Solicita (o reutiliza de la cache) un access token con los scopes dados.
     */
    public TokenOAuth2 solicitarToken(Collection<String> scopes) {
        String claveScopes = String.join(" ", scopes);
        TokenCacheado cacheado = cache.get(claveScopes);
        if (cacheado != null && Instant.now().isBefore(cacheado.recogerDespuesDe())) {
            return new TokenOAuth2(cacheado.valor(), cacheado.expiraEnMinutos());
        }
        TokenRespuesta respuesta = pedirToken(claveScopes);
        long expiraEnMinutos = respuesta.expiresIn() / 60;
        cache.put(claveScopes, new TokenCacheado(respuesta.accessToken(), expiraEnMinutos,
                Instant.now().plusSeconds(Math.max(0, respuesta.expiresIn() - MARGEN_EXPIRACION_SEGUNDOS))));
        log.info("evento=token_oauth2_obtenido scopes={} expiraEnSeg={}", claveScopes, respuesta.expiresIn());
        return new TokenOAuth2(respuesta.accessToken(), expiraEnMinutos);
    }

    private TokenRespuesta pedirToken(String scopes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(clienteId, clienteSecret);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> cuerpo = new LinkedMultiValueMap<>();
        cuerpo.add("grant_type", "client_credentials");
        cuerpo.add("scope", scopes);
        return restTemplate.postForEntity(tokenUrl, new HttpEntity<>(cuerpo, headers), TokenRespuesta.class)
                .getBody();
    }
}
