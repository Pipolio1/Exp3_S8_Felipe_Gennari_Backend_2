# Trazabilidad e idempotencia del procesamiento de eventos

Documento complementario al README: describe el ciclo de vida de los dos
identificadores que atraviesan la saga coreografiada (`requestId` y
`eventoId`), cómo se reconoce un evento ya tratado y las convenciones de
respuesta HTTP compartidas por los tres microservicios.

## 1. Ciclo de vida del requestId (trazabilidad end-to-end)

El `requestId` correlaciona una solicitud HTTP con todo lo que esta provoca:
llamadas internas, logs, respuestas de error y eventos de Kafka.

```
Cliente                    ms-operaciones                 ms-cuentas                  Kafka consumers
   │ X-Request-ID (opcional)     │                              │                            │
   │────────────────────────────▶│ RequestIdFilter              │                            │
   │                             │  · toma el header o genera   │                            │
   │                             │    un UUID                   │                            │
   │                             │  · MDC["requestId"]          │                            │
   │                             │  · lo devuelve en la         │                            │
   │                             │    respuesta                 │                            │
   │                             │        │ Feign (CuentasClientConfig)                      │
   │                             │        │ propaga X-Request-ID desde el MDC                │
   │                             │        ▼────────────────────▶│ RequestIdFilter reutiliza  │
   │                             │                              │ el mismo requestId → MDC   │
   │                             │ OperacionesService crea el evento:                         │
   │                             │   RetiroRealizadoEvent(..., requestId = MDC)               │
   │                             │        │ publica en retiros-realizados                     │
   │                             │        ▼─────────────────────────────────────────────────▶│
   │                             │                              │  cada consumidor pone        │
   │                             │                              │  evento.requestId en el MDC  │
   │                             │                              │  (try/finally lo limpia)     │
   │◀─ respuesta con X-Request-ID│                              │                            │
```

Reglas:

- **Generación**: `RequestIdFilter` (bank-common, `@Order(1)`) toma el header
  `X-Request-ID` del cliente; si no viene, genera un UUID. Siempre lo expone en
  el MDC (`requestId`) y lo devuelve en el header de respuesta.
- **Propagación síncrona**: el interceptor Feign de ms-operaciones copia el
  `requestId` del MDC al header `X-Request-ID` hacia ms-cuentas, cuyo
  `RequestIdFilter` lo reutiliza en vez de generar uno nuevo.
- **Propagación asíncrona**: el `requestId` viaja dentro de
  `RetiroRealizadoEvent`; cada consumidor Kafka lo coloca en el MDC al inicio
  del procesamiento y lo limpia en el `finally` (si el evento no lo trae, se
  genera un UUID para que el campo nunca quede vacío).
- **Consumo**: el patrón de log incluye `%X{requestId}` y `ErrorResponse` lo
  toma del MDC, de modo que cualquier error responde con el identificador de
  la traza que lo originó.

## 2. Ciclo de vida del eventoId (idempotencia)

Kafka garantiza entrega *at-least-once*: el mismo evento puede llegar
duplicado (reintentos, rebalanceos, confirmaciones tardías). El `eventoId` es
la clave que permite reconocer una solicitud ya tratada.

1. **Generación**: `OperacionesService.retirar` crea el `eventoId` (UUID) al
   construir `RetiroRealizadoEvent`, después de que ms-cuentas confirmó el
   cargo. Un retiro = un eventoId, aunque el evento se entregue varias veces.
2. **Publicación**: el productor es idempotente (`enable.idempotence=true`,
   `acks=all`), lo que evita duplicados *en el broker*; aun así, el consumidor
   debe asumir duplicados *en la entrega*.
3. **Reconocimiento de duplicados**: la tabla `eventos_procesados`
   (PK compuesta `evento_id + consumidor`, ver
   `scripts/sql/eventos_procesados.sql`) registra qué eventoId ya procesó cada
   consumidor. La PK compuesta permite que ms-operaciones y ms-transacciones
   procesen el mismo evento de forma independiente.
4. **Procesamiento atómico**: cada consumidor (y también el registro síncrono
   de degradación, ver §3) ejecuta en **una misma transacción**:
   `existsByEventoIdAndConsumidor` → si existe, se descarta y se loguea
   `retiro_duplicado_descartado`; si no, inserta el efecto de negocio
   (operación anual o transacción) **y** la marca. Si la transacción hace
   rollback, la marca no queda y el evento se reprocesa: nunca se pierde ni se
   duplica el efecto.
5. **Id legado de transacciones**: se asigna como `max(id)+1` para continuar
   la secuencia del sistema antiguo (la tabla legada admite ids de negocio
   repetidos, por lo que no se puede usar un índice único). La asignación es
   segura porque cada grupo consumidor procesa la partición del tópico con un
   solo hilo, y la marca de `eventos_procesados` garantiza que un mismo evento
   nunca genera dos registros. Si en el futuro se escala a varias particiones
   o instancias, este punto debe revisarse (p.ej. con un contador atómico).

## 3. El fallback síncrono también es idempotente

Cuando Kafka no está disponible (o el ack supera el timeout de 5 s), el
Circuit Breaker `publicacion-eventos` degrada al registro síncrono local
(`OperacionesService.registrarOperacion`). Este camino **también marca el
`eventoId` en `eventos_procesados`** dentro de la misma transacción, por lo
que:

- si el evento nunca llegó al broker: la operación quedó registrada una vez;
- si el evento sí llegó (timeout del ack): el consumidor lo detecta como ya
  procesado y lo descarta — no hay doble registro;
- si el evento ya había sido consumido antes del fallback: el registro
  síncrono se omite (`registro_sincrono_omitido`).

## 4. Convenciones de respuesta de las APIs

### Éxito

Cada endpoint devuelve su DTO con `ResponseEntity` y el código 200
(`POST /retiro`, `POST /login`) o 200 con cuerpo paginado (`PaginaDTO`) en los
listados. Los contratos por endpoint están documentados con OpenAPI
(`@Operation`/`@ApiResponses`) y visibles en `/swagger-ui.html` de cada
servicio.

### Error (estructura uniforme en los 3 servicios)

```json
{
  "codigo": "BANK-409-01",
  "error": "Saldo insuficiente",
  "requestId": "8f3c2a1b-…",
  "detalles": { "saldoActual": "3000.0" }
}
```

- `codigo`: identificador estable de máquina (`CodigoError`, bank-common);
  no cambia aunque cambie el mensaje.
- `error`: mensaje legible para humanos.
- `requestId`: correlación con los logs (ver §1).
- `detalles`: opcional; en errores de validación (400) trae los mensajes por
  campo.

### Tabla de códigos

| HTTP | CodigoError | Situación |
|---|---|---|
| 400 | `BANK-400-01` PARAMETROS_INVALIDOS | Parámetros de consulta fuera de rango o ausentes |
| 400 | `BANK-400-02` VALIDACION_FALLIDA | Body que no pasa Bean Validation (`detalles` por campo) |
| 400 | `BANK-400-03` SOLICITUD_MAL_FORMADA | JSON ilegible o tipo de parámetro incorrecto |
| 401 | `BANK-401-01` CREDENCIALES_INVALIDAS | Login rechazado |
| 401 | `BANK-401-02` API_KEY_INVALIDA | Header `X-API-KEY` ausente o incorrecto |
| 401 | `BANK-401-03` TOKEN_INVALIDO | JWT ausente, inválido o expirado |
| 403 | `BANK-403-01` SERVICIO_INCORRECTO | JWT emitido por otro microservicio |
| 403 | `BANK-403-02` ACCESO_DENEGADO | JWT sin el permiso que exige la operación |
| 404 | `BANK-404-01` CUENTA_NO_ENCONTRADA | La cuenta no existe |
| 404 | `BANK-404-02` RECURSO_NO_ENCONTRADO | Ruta inexistente |
| 405 | `BANK-405-01` METODO_NO_PERMITIDO | Método HTTP no soportado por el endpoint |
| 409 | `BANK-409-01` SALDO_INSUFICIENTE | El saldo no alcanza para el retiro/cargo |
| 429 | `BANK-429-01` LIMITE_EXCEDIDO | Rate limit agotado (incluye `Retry-After`) |
| 500 | `BANK-500-01` ERROR_INTERNO | Error no controlado (sin filtrar detalles internos) |
| 503 | `BANK-503-01` SERVICIO_NO_DISPONIBLE | Dependencia caída vía fallback de Circuit Breaker |

Todos los errores —incluidos los que producen los interceptores de API key y
rate limit, y los rechazos 401/403 del Resource Server OAuth2 (token ausente,
inválido o sin el scope requerido)— salen con esta misma estructura, resueltos
de forma centralizada por el `GlobalExceptionHandler` de cada servicio y por
los entry points de seguridad de su `SecurityConfig`.
