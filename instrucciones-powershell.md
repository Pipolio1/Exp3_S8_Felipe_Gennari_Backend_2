# Guía de ejecución y evidencias (PowerShell) — Semana 8

Instrucciones paso a paso para levantar el sistema con Docker y generar las
evidencias de la entrega. Ejecutar en **PowerShell**, en orden. Notas importantes:

- Usar **`curl.exe`** (no `curl`, que en PowerShell es un alias distinto).
- El login usa los campos **`usuario`** y **`clave`**.
- Si una cuenta ya no tiene saldo suficiente por pruebas anteriores, elegir otra
  (ver nota al final).

## 0. Ir a la carpeta del proyecto

```powershell
cd "ubicacion del archivo"
```

## 1. Levantar TODA la plataforma con Docker (un solo comando)

```powershell
# Si existe el contenedor mysql-bank de semanas anteriores, detenerlo primero:
docker stop mysql-bank

docker compose up -d --build
docker compose ps
```

La primera corrida tarda unos minutos (compila los módulos dentro de las
imágenes e importa la base legada). Los microservicios quedan `healthy` al
cabo de ~1 minuto; verificar con `docker compose ps` hasta que ms-cuentas,
ms-transacciones y ms-operaciones digan `(healthy)`.

📸 Capturas sugeridas: `docker compose ps` con los 9 contenedores arriba,
y `docker images` con las 6 imágenes construidas (config-server, eureka-server,
auth-server, ms-cuentas, ms-transacciones, ms-operaciones).

## 2. Evidencia OAuth2 (Authorization Server + Resource Servers)

```powershell
# Token directo del Authorization Server (flujo client_credentials)
curl.exe -s -u ms-operaciones:operaciones-secret-789 -X POST http://localhost:9000/oauth2/token `
  -d "grant_type=client_credentials" -d "scope=CONSULTA RETIRO"

# JWK publico con la clave que firma los tokens
curl.exe -s http://localhost:9000/oauth2/jwks

# Sin token -> 401 (BANK-401-03)
curl.exe -s -i http://localhost:8083/api/operaciones/cuenta/121 -H "X-API-KEY: operaciones-key-789"
```

```powershell
# Login de un usuario SIN permiso de retiro y retiro -> 403 (BANK-403-02)
$loginC = curl.exe -s -X POST http://localhost:8083/api/operaciones/auth/login `
  -H "X-API-KEY: operaciones-key-789" -H "Content-Type: application/json" `
  -d '{"usuario":"consultor","clave":"co123"}' | ConvertFrom-Json
curl.exe -s -i -X POST http://localhost:8083/api/operaciones/retiro `
  -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $($loginC.token)" `
  -H "Content-Type: application/json" -d '{"cuentaId": 121, "monto": 1000}'
```

📸 Capturas sugeridas: el JSON del token OAuth2, el 401 sin token y el 403
por scope insuficiente.

## 3. Evidencia del flujo de eventos (retiro → Kafka → ambos consumidores)

```powershell
# Login en ms-operaciones (el token lo emite el auth-server via OAuth2)
$login = curl.exe -s -X POST http://localhost:8083/api/operaciones/auth/login `
  -H "X-API-KEY: operaciones-key-789" -H "Content-Type: application/json" `
  -d '{"usuario":"operador","clave":"op123"}' | ConvertFrom-Json
$token = $login.token

# Ejecutar retiro (publica el evento)
curl.exe -s -X POST http://localhost:8083/api/operaciones/retiro `
  -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" -d '{"cuentaId": 121, "monto": 5000}'
```

```powershell
# Verificar efecto en ms-operaciones (operacion anual registrada por el consumidor)
curl.exe -s "http://localhost:8083/api/operaciones/cuenta/121" `
  -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $token"

# Login en ms-transacciones
$loginT = curl.exe -s -X POST http://localhost:8082/api/transacciones/auth/login `
  -H "X-API-KEY: transacciones-key-456" -H "Content-Type: application/json" `
  -d '{"usuario":"analista","clave":"an123"}' | ConvertFrom-Json
$tokenT = $loginT.token

# Verificar efecto en ms-transacciones (el retiro aparece como transaccion)
curl.exe -s http://localhost:8082/api/transacciones/recientes `
  -H "X-API-KEY: transacciones-key-456" -H "Authorization: Bearer $tokenT"
```

```powershell
# Evidencia en la base de datos (evento procesado por ambos consumidores)
docker exec -i mysql-bank-compose mysql -uroot -proot bank_legacy -e "SELECT * FROM operaciones_anuales ORDER BY registro_id DESC LIMIT 2; SELECT * FROM transacciones ORDER BY registro_id DESC LIMIT 2; SELECT * FROM eventos_procesados;"
```

📸 Capturas sugeridas: respuesta del retiro, ambos GET, la consulta SQL, los logs
(`docker compose logs ms-operaciones`, `docker compose logs ms-transacciones`)
con `evento=retiro_publicado` / `operacion_registrada_desde_evento` /
`transaccion_registrada_desde_evento`, kafka-ui (http://localhost:8090 →
Topics → `retiros-realizados`) y Eureka (http://localhost:8761 con los 3
microservicios registrados).

## 4. Evidencia de tolerancia a fallos (circuit breaker)

```powershell
# Detener ms-cuentas (simula servicio caido)
docker stop ms-cuentas

# Repetir el retiro varias veces: los primeros tardan ~4s (reintentos),
# luego el circuito ABRE y falla en milisegundos
1..5 | ForEach-Object {
  curl.exe -s -w "`nHTTP %{http_code} - %{time_total}s`n" -X POST http://localhost:8083/api/operaciones/retiro `
    -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $token" `
    -H "Content-Type: application/json" -d '{"cuentaId": 121, "monto": 1000}'
}

# Estado de los circuit breakers (debe mostrar ms-cuentas en OPEN)
curl.exe -s http://localhost:8083/actuator/circuitbreakers
```

```powershell
# Recuperacion: volver a levantar ms-cuentas, esperar ~40s y reintentar el retiro
docker start ms-cuentas
# (pasados ~40s, cuando vuelve a estar healthy)
curl.exe -s -X POST http://localhost:8083/api/operaciones/retiro `
  -H "X-API-KEY: operaciones-key-789" -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" -d '{"cuentaId": 121, "monto": 1000}'
```

📸 Capturas sugeridas: los 503 con tiempos (4s vs 0.006s), el JSON del actuator
con `"state":"OPEN"`, y el retiro exitoso tras la recuperación.

## 5. Detener la plataforma

```powershell
docker compose down        # conserva los datos
docker compose down -v     # reinicia la base desde cero la proxima vez
```

## Nota: elegir una cuenta con saldo

```powershell
docker exec -i mysql-bank-compose mysql -uroot -proot bank_legacy -e "SELECT cuenta_id, saldo FROM cuentas ORDER BY registro_id DESC LIMIT 10;"
```

Usar una `cuentaId` con saldo suficiente en los comandos de retiro (y ajustar los
GET de verificación a esa misma cuenta).

## Alternativa: ejecución local sin contenedores para los microservicios

Ver la seccion "Instrucciones de ejecución (local, sin contenedores)" del
README.md: MySQL y Kafka en Docker, y los 6 servicios con
`mvn -pl <modulo> spring-boot:run` (config-server, eureka-server, auth-server,
ms-cuentas, ms-transacciones, ms-operaciones, en ese orden).
