# Banco XYZ — Microservicios resilientes, orientados a eventos y seguros con OAuth2

Sistema bancario distribuido (Experiencia 3, Semana 8 — Desarrollo Backend III, PBY2203).
Continuidad del proyecto de la semana 7, al que se agrega **seguridad OAuth 2.0**
(Spring Authorization Server + Resource Servers) y **conteinerización completa**
(Dockerfiles por servicio + `docker-compose.yml` que orquesta toda la plataforma).
Se mantienen la **mensajería asíncrona con Apache Kafka** (patrón Saga con
coreografía) y la **tolerancia a fallos con Resilience4j** (Circuit Breaker + Retry).

## Objetivo

Demostrar una arquitectura de microservicios resiliente, orientada a eventos y
segura, lista para un entorno Cloud: un solo comando (`docker compose up -d
--build`) levanta la plataforma completa — base de datos, broker, plataforma
Spring Cloud y microservicios de negocio — con la autorización centralizada en
un Authorization Server OAuth2 que emite los access tokens JWT que protegen
cada servicio.

## Arquitectura de eventos elegida

**Kafka + patrón Saga con coreografía + entrega at-least-once con consumidores idempotentes.**

¿Por qué?

- El retiro es una **transacción distribuida** (ms-operaciones coordina, ms-cuentas
  descuenta el saldo, y dos historiales deben registrarse). El patrón Saga la divide
  en pasos locales encadenados por eventos.
- Se eligió **coreografía** (sin orquestador central): cada servicio es autónomo y
  reacciona a los eventos del tópico. El material del curso recomienda Kafka para
  coreografías por su escalabilidad y persistencia de eventos.
- Se eligió **Kafka sobre JMS** porque el log persistente del tópico retiene los
  eventos aunque un consumidor esté caído (y permite reprocesarlos), escala por
  particiones y admite múltiples grupos de consumidores independientes sobre el
  mismo evento. JMS elimina el mensaje al consumirlo y está pensado para
  comunicación punto a punto transaccional.
- La entrega **at-least-once** puede duplicar mensajes; por eso cada evento lleva
  un `eventoId` (UUID) y cada consumidor lo marca en la tabla `eventos_procesados`
  dentro de la misma transacción que el efecto de negocio (**consumidor idempotente**).

Además, la mensajería resuelve una limitación real del proyecto de la semana 6:
antes, si fallaba el registro local después de ejecutado el cargo, **el retiro
quedaba sin historial**. Ahora el evento persiste en Kafka y el consumidor lo
reintenta hasta registrarlo.

## Flujo de la saga (retiro)

```
POST /api/operaciones/retiro (ms-operaciones)
  → 1. consultarSaldo  (Feign → ms-cuentas)   [Circuit Breaker + Retry]
  → 2. ejecutarCargo   (Feign → ms-cuentas)   [Circuit Breaker + Retry]
  → 3. publica RetiroRealizadoEvent en tópico "retiros-realizados" (key = cuentaId)
       [Circuit Breaker publicacion-eventos;
        fallback: registro síncrono local (degrada al comportamiento de la semana 6)]
  → responde 200 con el saldo restante

Tópico retiros-realizados — dos grupos de consumidores independientes:
  ├─ grupo ms-operaciones   → registra la operación anual (tabla operaciones_anuales)
  └─ grupo ms-transacciones → registra la transacción  (tabla transacciones,
                              visible en GET /api/transacciones/recientes)
```

El evento `RetiroRealizadoEvent` contiene: `eventoId` (UUID), `cuentaId`, `monto`,
`saldoRestante`, `fecha` y `requestId` (trazabilidad con la solicitud HTTP original).

El ciclo de vida del `requestId` y del `eventoId`, la idempotencia de los
consumidores y las convenciones de respuesta HTTP de los tres servicios están
documentados en
[docs/trazabilidad-e-idempotencia.md](docs/trazabilidad-e-idempotencia.md).

## Estructura del código

```
Exp3_S6_Grupo9-main/
├── pom.xml                        # POM padre (Java 21, Spring Boot 3.3.5, Spring Cloud 2023.0.5)
├── docker-compose.yml             # Orquesta TODO: MySQL + Kafka + 6 servicios
├── .dockerignore
├── scripts/sql/
│   ├── bank_legacy.sql            # dump de la base legada (datos de prueba)
│   └── eventos_procesados.sql     # tabla de idempotencia de consumidores
├── docs/
│   └── trazabilidad-e-idempotencia.md  # ciclo de vida de requestId/eventoId y contratos de error
├── bank-common/                   # contrato compartido
│   └── .../common/
│       ├── event/RetiroRealizadoEvent.java   # evento de dominio del retiro
│       ├── dto/ (CargoRequest, SaldoCuentaDTO)
│       ├── exception/ (ErrorResponse, CodigoError, excepciones de negocio)
│       └── filter/RequestIdFilter.java       # trazabilidad X-Request-ID (MDC)
├── config-server/                 # configuración centralizada (puerto 8888) + Dockerfile
│   └── src/main/resources/config-repo/       # yml compartido + uno por servicio
├── eureka-server/                 # service discovery (puerto 8761) + Dockerfile
├── auth-server/                   # Authorization Server OAuth2 (puerto 9000) + Dockerfile
│   └── .../authserver/
│       └── config/AuthorizationServerConfig.java  # clientes, JWK RSA, issuer
├── ms-cuentas/                    # dueño del saldo (puerto 8081) + Dockerfile
│   └── .../cuentas/
│       ├── config/SecurityConfig.java            # Resource Server OAuth2 (scopes)
│       └── security/OAuth2TokenService.java      # cliente OAuth2 (login)
├── ms-transacciones/              # historial (puerto 8082) + Dockerfile
│   └── .../transacciones/
│       ├── event/RetiroEventConsumer.java    # consume retiros → registra transacción
│       ├── model/EventoProcesado.java        # idempotencia
│       └── config/SecurityConfig.java        # Resource Server OAuth2
└── ms-operaciones/                # orquestador del retiro (puerto 8083) + Dockerfile
    └── .../operaciones/
        ├── event/RetiroEventProducer.java    # publica RetiroRealizadoEvent
        ├── event/RetiroEventConsumer.java    # consume retiros → registra operación anual
        ├── service/OperacionesService.java   # saga: saldo → cargo → evento
        ├── client/CuentasClientConfig.java   # Feign: API key + Bearer OAuth2 (INTERNO)
        └── config/SecurityConfig.java        # Resource Server OAuth2
```

## Seguridad OAuth 2.0 (nuevo en S8)

Se adopta el modelo de delegación de seguridad de OAuth 2.0 con
**Spring Authorization Server** (el proyecto recomendado por el curso):

```
Cliente (curl / app)
   │ 1) POST /api/{servicio}/auth/login  (usuario + clave, con X-API-KEY)
   ▼
Microservicio (Resource Server)          auth-server (Authorization Server, :9000)
   │ 2) valida credenciales del usuario        ▲
   │ 3) POST /oauth2/token ────────────────────┤  grant_type=client_credentials
   │    (client_id/secret del servicio;        │  emite access token JWT (RSA)
   │     scopes = permisos del usuario)        │
   │◀────────────────────────────── access_token (claim scp con los permisos)
   │ 4) responde { token, tipo: "Bearer", expiraEnMinutos }
   ▼
Cliente llama a la API con Authorization: Bearer <token>
   → cada Resource Server valida firma (JWK del auth-server), issuer,
     expiración y el scope que exige la operación
```

Piezas del flujo:

- **Authorization Server** (`auth-server`, puerto 9000): único emisor de
  tokens. Registra a los microservicios como clientes OAuth2
  (`client_secret_basic`, grant `client_credentials`), firma los JWT con un
  par RSA y publica la clave pública en `/oauth2/jwks`.
- **Resource Servers** (`ms-cuentas`, `ms-transacciones`, `ms-operaciones`):
  validan cada token contra el JWK del auth-server (sin compartir secretos de
  firma) y autorizan por scope: `CONSULTA`, `REPORTES`, `RETIRO` (solo
  operaciones) e `INTERNO` (endpoints inter-servicio de cuentas).
- **Llamadas inter-servicio**: ms-operaciones obtiene su propio token
  (`scope=INTERNO`) y lo adjunta como `Authorization: Bearer` en las llamadas
  Feign a los endpoints internos de ms-cuentas, además del API key vigente.
- Los rechazos mantienen el contrato uniforme de errores: 401
  `BANK-401-03` (token ausente/inválido/expirado) y 403 `BANK-403-02`
  (token sin el scope requerido).

### Cómo probar OAuth2

```bash
# Token directo del Authorization Server (flujo client_credentials)
curl -u ms-operaciones:operaciones-secret-789 -X POST http://localhost:9000/oauth2/token \
  -d "grant_type=client_credentials" -d "scope=CONSULTA RETIRO"

# Sin token → 401
curl -i http://localhost:8083/api/operaciones/cuenta/1001 -H "X-API-KEY: operaciones-key-789"

# Con token sin el scope RETIRO (usuario "consultor") → 403 al retirar
# (ver flujo completo más abajo)
```

## Tolerancia a fallos (Resilience4j)

| Mecanismo | Dónde | Comportamiento |
|---|---|---|
| Circuit Breaker `ms-cuentas` | ms-operaciones → Feign | Si ms-cuentas falla, responde 503; el circuito se abre al 50 % de fallos |
| **Retry `ms-cuentas`** (nuevo) | ms-operaciones → Feign | 3 intentos con 500 ms de espera ante fallos transitorios, antes de abrir el circuito |
| Circuit Breaker `publicacion-eventos` (nuevo) | ms-operaciones → Kafka | Si el broker no está disponible, degrada al registro síncrono local |
| Circuit Breaker `persistencia-*` | los 3 ms → MySQL | Degradación controlada ante fallos de la base de datos |
| Reintentos del consumidor (nuevo) | listeners Kafka | 3 reintentos cada 2 s; el evento permanece en el tópico hasta ser procesado |
| Productor idempotente + `acks=all` | ms-operaciones → Kafka | Sin duplicados ni pérdida en la publicación |
| Consumidores idempotentes | listeners Kafka | `eventos_procesados` evita efectos duplicados (at-least-once) |

Estado de los circuit breakers en vivo: `GET /actuator/circuitbreakers` de cada servicio.

## Prerrequisitos

- Docker (recomendado: toda la plataforma corre con `docker compose`)
- Para ejecución local sin contenedores: Java 21 y Maven 3.9+

## Instrucciones de ejecución (Docker — recomendado)

Un solo comando construye las 6 imágenes y levanta la plataforma completa
(MySQL con los datos legados, Kafka, kafka-ui, config-server, eureka-server,
auth-server y los 3 microservicios de negocio):

```bash
docker compose up -d --build
```

Notas:

- Si existe el contenedor `mysql-bank` de semanas anteriores, detenerlo antes
  (`docker stop mysql-bank`): usa el mismo puerto 3306.
- La primera corrida tarda unos minutos: compila los módulos dentro de las
  imágenes e importa el dump `scripts/sql/bank_legacy.sql`.
- Estado de la plataforma: `docker compose ps` (los microservicios muestran
  `healthy` cuando están listos; la primera vez pueden tardar ~1 minuto).
- kafka-ui queda en http://localhost:8090 y Eureka en http://localhost:8761.

Para detener todo: `docker compose down` (agregar `-v` si se quiere reiniciar
la base desde cero).

## Instrucciones de ejecución (local, sin contenedores)

1. **Base de datos**: contenedor MySQL de la semana 6 en marcha, y crear la tabla
   de idempotencia (una sola vez):
   ```bash
   docker start mysql-bank
   docker exec -i mysql-bank mysql -uroot -proot bank_legacy < scripts/sql/eventos_procesados.sql
   ```

2. **Kafka**:
   ```bash
   docker compose up -d kafka kafka-ui
   ```

3. **Compilar** (desde la raíz del proyecto):
   ```bash
   mvn clean package -DskipTests
   ```

4. **Levantar los servicios** (cada uno en su terminal, en este orden):
   ```bash
   mvn -pl config-server spring-boot:run     # puerto 8888
   mvn -pl eureka-server spring-boot:run     # puerto 8761
   mvn -pl auth-server spring-boot:run       # puerto 9000
   mvn -pl ms-cuentas spring-boot:run        # puerto 8081
   mvn -pl ms-transacciones spring-boot:run  # puerto 8082
   mvn -pl ms-operaciones spring-boot:run    # puerto 8083
   ```

## Cómo probar el flujo de eventos

```bash
# 1) Login en ms-operaciones (obtener JWT)
TOKEN=$(curl -s -X POST http://localhost:8083/api/operaciones/auth/login \
  -H "X-API-KEY: operaciones-key-789" -H "Content-Type: application/json" \
  -d '{"usuario":"operador","clave":"op123"}' | jq -r .token)

# 2) Ejecutar un retiro (publica el evento en Kafka)
curl -s -X POST http://localhost:8083/api/operaciones/retiro \
  -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cuentaId": 1001, "monto": 5000}'

# 3) Verificar los efectos asíncronos del evento:
curl -s "http://localhost:8083/api/operaciones/cuenta/1001" \
  -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $TOKEN"

TOKENT=$(curl -s -X POST http://localhost:8082/api/transacciones/auth/login \
  -H "X-API-KEY: transacciones-key-456" -H "Content-Type: application/json" \
  -d '{"usuario":"analista","clave":"an123"}' | jq -r .token)

curl -s http://localhost:8082/api/transacciones/recientes \
  -H "X-API-KEY: transacciones-key-456" -H "Authorization: Bearer $TOKENT"
```

En los logs de ms-operaciones se ve `evento=retiro_publicado` y
`evento=operacion_registrada_desde_evento`; en ms-transacciones,
`evento=transaccion_registrada_desde_evento`. El mensaje también es visible en
kafka-ui (http://localhost:8090).

## Cómo demostrar la tolerancia a fallos

- **ms-cuentas caído**: detener ms-cuentas y repetir el retiro → responde 503
  (`ServicioNoDisponibleException` vía fallback); en los logs se ven los
  reintentos de `@Retry` y la apertura del circuito en
  `GET http://localhost:8083/actuator/circuitbreakers`.
- **MySQL caído durante el consumo**: el consumidor reintenta 3 veces cada 2 s
  (log) y el evento no se pierde; al volver la base, se registra.
- **Kafka caído**: el retiro se confirma igual; el fallback del Circuit Breaker
  `publicacion-eventos` registra la operación de forma síncrona
  (`evento=publicacion_fallida` en el log).
