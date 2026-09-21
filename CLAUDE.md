# MesaQR

API de autopedidos por QR para restaurantes (Java 21 / Spring Boot 3.3.0 / Maven). Un comensal escanea el QR de su mesa, recibe un token de sesión (24h), pide platillos vía REST y ve actualizaciones en tiempo real (nuevos items, cambios de estado de mesa) vía STOMP/WebSocket. Concurrencia entre comensales de la misma mesa se controla con bloqueo pesimista (`PESSIMISTIC_WRITE`) + reintentos con backoff (Spring Retry).

## Build, test, run

```bash
./mvnw clean install          # build + tests (perfil "test", H2 en memoria vía application-test.properties)
./mvnw test                   # solo tests
./mvnw spring-boot:run                                    # perfil default (application.properties, espera Postgres en localhost:5432)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev      # perfil dev
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod     # perfil prod (requiere DB_HOST, DB_PASSWORD, WEBHOOK_SECRET por env)
```

Infra local (Postgres real, `restaurant_db`, usuario/pass `restaurant`, puerto 5432):
```bash
docker-compose up -d          # levanta Postgres; Flyway migra en el arranque de la app
```

`docker-compose.yml` también define un servicio `app` que construye la imagen (`Dockerfile`, build multistage con `mvnw package -DskipTests`) para correr todo en contenedores.

No hay Makefile. `test_e2e.ps1` en la raíz es un script PowerShell de pruebas end-to-end manuales contra la API corriendo. `api-test.http` tiene requests de ejemplo (REST Client de VS Code/IntelliJ).

## Perfiles Spring

| Perfil | Archivo | DB | Uso |
|---|---|---|---|
| (default) | `application.properties` | Postgres `restaurant_db` en `${DB_HOST:localhost}` | Config base compartida; `ddl-auto=validate` (el esquema lo crea Flyway, nunca Hibernate) |
| `dev` | `application-dev.properties` | Postgres local | Logging verboso (SQL, web), Actuator completo expuesto, CORS permisivo (incluye `localhost:5173`) |
| `prod` | `application-prod.properties` | Postgres vía env vars (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` obligatoria) | Logging reducido, Actuator solo `health`+`prometheus`, `forward-headers-strategy=framework` |
| `h2` | `application-h2.properties` | H2 en memoria (modo PostgreSQL) | Correr la app localmente sin Docker/Postgres; Flyway deshabilitado, `ddl-auto=create-drop`, consola H2 en `/h2-console` |
| `test` | `src/test/resources/application-test.properties` | H2 en memoria (modo PostgreSQL) | Perfil de los tests JUnit (`@ActiveProfiles("test")`), Flyway deshabilitado, rate limits relajados |

Postgres (`restaurant_db`) es la base de datos real del proyecto en dev/prod; H2 solo se usa para tests y para correr la app sin infraestructura (perfil `h2`). El esquema vive en `src/main/resources/db/migration/V1..V5__*.sql` (Flyway) — nunca editar una migración ya aplicada, siempre agregar una nueva `Vn__`.

## Arquitectura

Monolito modular, paquete raíz `com.restaurant`:

- `controlador/` — `@RestController` REST (`ControladorMesa`, `ControladorPedido`, `ControladorPago`, `ControladorQR`) + `ControladorVistaMenu` (Thymeleaf). Todas las rutas de comensal (`/api/pedidos/mesa/{id}/...`) exigen header `X-Session-Token`, validado en cada endpoint vía `ServicioMesa.validarToken(mesaId, token)` antes de tocar el servicio.
- `servicio/` — lógica de negocio: `ServicioMesa`, `ServicioPedido`, `ServicioPago`, `ServicioPlatillo`, `ServicioQR`. Aquí vive el bloqueo pesimista (`findByIdConBloqueo` en los repositorios) y la publicación de eventos WebSocket.
- `modelo/` — entidades JPA (`Mesa`, `Pedido`, `DetallePedido`, `Pago`, `Platillo`) y enums de estado (`EstadoMesa`, `EstadoPedido`, `EstadoPago`, `MetodoPago`).
- `repositorio/` — Spring Data JPA; los métodos de escritura usan `@Lock(PESSIMISTIC_WRITE)` (`findByIdConBloqueo`).
- `dto/` y `dto/eventos/` — DTOs de request/response y los eventos que viajan por WebSocket.
- `configuracion/` — `ConfiguracionWebSocket`, `ConfiguracionWeb` (CORS), `IndicadorSaludBaseDatos` (Actuator health custom).
- `excepcion/` — excepciones de dominio + `ManejadorGlobalExcepciones` (`@RestControllerAdvice`).

### WebSocket (la pieza distintiva)

STOMP sobre SockJS, **no WebSocket plano**. Configurado en `configuracion/ConfiguracionWebSocket.java`:

- Endpoint: `/ws` (con `.withSockJS()`), orígenes permitidos = `restaurant.cors.allowed-origins`.
- Broker simple en memoria (`enableSimpleBroker("/topic")`), prefijo de app `/app` (aunque actualmente no hay ningún `@MessageMapping`: el flujo es unidireccional servidor→cliente, no hay mensajes entrantes de los clientes por STOMP).
- Autenticación en el `CONNECT` frame: un `ChannelInterceptor` en `configureClientInboundChannel` exige el header nativo STOMP `X-Session-Token` y lo valida contra `ServicioMesa.existeTokenValido(token)`; si falta o es inválido, lanza `IllegalArgumentException` y rechaza la conexión.
- Los servicios (`ServicioMesa`, `ServicioPedido`) publican actualizaciones con `SimpMessagingTemplate.convertAndSend("/topic/mesas", evento)` después de cada cambio de estado relevante (reservar/liberar mesa, crear/agregar/cancelar pedido).
- Eventos (`dto/eventos/`): `EventoMesa` es la clase base abstracta con `@JsonTypeInfo` (discriminador `"tipo"`); subtipos `EventoCambioEstadoMesa` (`CAMBIO_ESTADO`) y `EventoActualizacionPedido` (`ACTUALIZACION_PEDIDO`). El cliente distingue el tipo de evento por el campo `tipo` del JSON recibido en `/topic/mesas`.
- Todos los comensales/personal suscritos a `/topic/mesas` reciben todos los eventos (no hay topics por mesa individual) — el filtrado por `mesaId`/`numeroDeMesa` es responsabilidad del cliente.

## Convenciones

- Todo el código (clases, métodos, variables, comentarios/Javadoc) está en **español**: `Mesa`, `Pedido`, `ServicioPedido`, `validarToken`, etc. Mantener esa convención en código nuevo.
- Escrituras concurrentes sobre `Mesa`/`Pedido` deben pasar por los métodos `findByIdConBloqueo` (bloqueo pesimista) dentro de un `@Transactional`, no por `findById`.
- Cualquier endpoint de comensal debe validar `X-Session-Token` con `ServicioMesa.validarToken` antes de mutar estado.
- Rate limiting con Resilience4j ya está anotado a nivel de controlador (`@RateLimiter(name = "pedido")`, `name = "pago"`); los límites por perfil están en cada `application-*.properties`.
- Migraciones nuevas van en `src/main/resources/db/migration/` como `V{n}__descripcion.sql`, nunca modificar una `Vn` existente.
- `.mcp.json` ya define servidores MCP de Postgres (lee `restaurant_db` en vivo) y GitHub, ambos deshabilitados hasta configurar `POSTGRES_CONNECTION_STRING` / `GITHUB_PERSONAL_ACCESS_TOKEN` como variables de entorno.
