# MEJORAS.md — Plan de mejora MesaQR API

> Auditoría completa del proyecto. 28 puntos de mejora distribuidos en 5 sprints.

---

## Sprint 1 — Seguridad

**Objetivo**: que nadie pueda tomar control ajeno ni espiar datos.

- [x] 1.1 **Quitar token de la URL del QR** — pasarlo como fragment (`#token=...`) para que el servidor no lo loguee ni el navegador lo comparta. Modificar `ServicioQR.java:15` y `menu.html` para leerlo del hash.
- [x] 1.2 **Proteger WebSocket** — requerir token vía handshake STOMP. El cliente lo envía como header `X-Session-Token` al conectar, `ConfiguracionWebSocket` lo valida con un `ChannelInterceptor`.
- [x] 1.3 **Restringir CORS** — cambiar `setAllowedOriginPatterns("*")` por orígenes específicos en `application.properties` (`restaurant.cors.allowed-origins`).
- [x] 1.4 **Verificar firma del webhook** — implementar HMAC-SHA256 en `ServicioPago.manejarWebhook()` usando un secreto configurable.
- [x] 1.5 **Externalizar credenciales** — mover `spring.datasource.password` a variable de entorno `${DB_PASSWORD}` con valor default solo para dev.
- [x] 1.6 **Agregar rate limiting** — usar Resilience4j con anotación `@RateLimiter` en endpoints de pago y pedido.

---

## Sprint 2 — Lógica de negocio

**Objetivo**: que los flujos no tengan agujeros semánticos.

- [x] 2.1 **Evitar pedidos duplicados** — agregar validación en `ServicioPedido.crearPedidoParaMesa()` que revise si ya existe un pedido ABIERTO para esa mesa.
- [x] 2.2 **Confirmación de pago en efectivo** — el pago en efectivo queda `PENDIENTE` igual que QR, y solo el mesero (o admin) lo confirma con un endpoint `/api/pagos/{id}/confirmar-efectivo`.
- [x] 2.3 **Activar `PedidoYaPagadoException`** — lanzarla en `obtenerPedidoActivo` cuando el pedido existe pero no está ABIERTO.
- [x] 2.4 **Agregar cálculo de IVA y propina** — sumar 16% IVA o 10% servicio según configuración del restaurante en `application.properties`.
- [x] 2.5 **Endpoint cancelar pedido** — `PUT /api/pedidos/mesa/{mesaId}/cancelar` que pase el pedido a CANCELADO y libere la mesa.
- [x] 2.6 **Implementar reservas o eliminar `RESERVADA`** — `PUT /api/mesas/{id}/reservar` + `PUT /api/mesas/{id}/liberar`.

---

## Sprint 3 — Validación y robustez

**Objetivo**: que ningún input inválido llegue a la lógica de negocio.

- [x] 3.1 **Validar todos los DTOs** — `@NotNull` en `platilloId`, `@Min(1)` en `cantidad`, `@NotNull @Min(1)` en `numeroDeMesa`, `@NotNull` en `capacidad`. Además `@Size` en `notas` y `tokenProveedor`.
- [x] 3.2 **Agregar `default` en switch de pagos** — lanzar `UnsupportedOperationException` con mensaje claro si el enum tiene un valor nuevo. Además validaciones de estado/método en `confirmarPagoQR` y `confirmarPagoEfectivo`.
- [x] 3.3 **Unificar `mesaId` en `SolicitudPagoDTO`** — agregado al body del DTO como campo opcional con fallback al `@PathVariable`.
- [x] 3.4 **Agregar validación de integridad de BD al startup** — Spring Actuator + health indicator personalizado que verifica tablas y migraciones Flyway.

---

## Sprint 4 — Arquitectura y código

**Objetivo**: limpiar deuda técnica y preparar para producción.

- [x] 4.1 **Reducir doble save en creación de mesa** — verificado que solo hay un `save()` en `crearMesa()`. No se requería cambio.
- [x] 4.2 **Manejar headers de proxy** — registrar `ForwardedHeaderFilter` en `ConfiguracionWeb.java` para que la URL del QR funcione detrás de Nginx/Traefik.
- [x] 4.3 **Reemplazar `RuntimeException` por excepciones de dominio** — `RecursoNoEncontradoException` creada y usada en todos los servicios. `QrException` envuelve errores de ZXing.
- [x] 4.4 **Externalizar constantes a properties** — tamaño QR, duración token, reintentos, backoff, webhook secret → `application.properties`.
- [x] 4.5 **Agregar `@Transactional(readOnly = true)`** — aplicado en `ServicioPlatillo` y en `obtenerResumenPedido` usando query sin lock pesimista.
- [x] 4.6 **Firmar método `generarQR` sin `throws Exception`** — envolver `ZXingException` en `QrException`.

---

## Sprint 5 — Testing, DevOps y observabilidad

**Objetivo**: despliegue profesional con métricas y cobertura.

### Testing
- [x] 5.1 **Tests unitarios de servicios** — `ServicioPedidoTest`, `ServicioPagoTest`, `ServicioMesaTest` con Mockito.
- [x] 5.2 **Tests de integración** — `FlujoCompletoPedidoTest` con H2 en modo PostgreSQL, flujo completo mesa→pedido→pago.
- [x] 5.3 **Tests de concurrencia** — `ConcurrenciaPedidoTest` con `ExecutorService` multi-hilo para validar locks pesimistas.

### Observabilidad
- [x] 5.4 **Agregar Spring Actuator** — health checks, métricas, info. Exponer `/actuator/health` y `/actuator/metrics`.
- [x] 5.5 **Agregar Micrometer + Prometheus** — dependencia `micrometer-registry-prometheus` + endpoint `/actuator/prometheus`.
- [x] 5.6 **Graceful shutdown** — `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s`.

### DevOps
- [x] 5.7 **Containerizar la app** — `Dockerfile` multi-stage y servicio en `docker-compose.yml` con health check y depends_on.
- [x] 5.8 **Perfiles Spring** — `application-dev.properties`, `application-prod.properties` con configs separadas.

### Código
- [x] 5.9 **Separar CSS y JS de menu.html** — estilos a `static/css/menu.css` y JS a `static/js/menu.js`, cargados via Thymeleaf.
- [x] 5.10 **Javadoc en clases públicas** — documentación en español en todos los servicios y DTOs.

---

## Progreso por sprint

| Sprint | Área | Estado | Completado |
|--------|------|--------|------------|
| 1 | Seguridad | ✅ Completado | 6/6 |
| 2 | Lógica de negocio | ✅ Completado | 6/6 |
| 3 | Validación y robustez | ✅ Completado | 4/4 |
| 4 | Arquitectura y código | ✅ Completado | 6/6 |
| 5 | Testing, DevOps y observabilidad | ✅ Completado | 10/10 |

**Total**: 28/28 — 100% ✅
