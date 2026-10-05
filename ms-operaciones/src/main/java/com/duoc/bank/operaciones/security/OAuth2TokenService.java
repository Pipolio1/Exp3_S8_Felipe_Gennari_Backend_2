package com.duoc.bank.operaciones.security;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente OAuth2 del microservicio de operaciones: solicita access tokens al
 * Authorization Server con el flujo client_credentials (RFC 6749, seccion 4.4),
 * autenticandose con las credenciales del cliente registrado para este servicio
 * ({@code seguridad.cliente-id} / {@code seguridad.cliente-secret}).
 *
 * Se usa en dos puntos: el login (los permisos del usuario se solicitan como
 * scopes del token) y las llamadas internas a ms-cuentas via Feign (scope
 * INTERNO, ver {@code CuentasClientConfig}). Los tokens se cachean hasta 30 s
 * antes de su expiracion para no golpear al auth-server en cada solicitud.
 */
@Service
public class OAuth2TokenService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2TokenService.class);
    private static final long MARGEN_EXPIRACION_SEGUNDOS = 30;
    private static final List<String> SCOPES_INTERNOS = List.of("INTERNO");

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

    /** Token con scope INTERNO para las llamadas inter-servicio a ms-cuentas. */
    public String tokenInterno() {
        return solicitarToken(SCOPES_INTERNOS).valor();
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
