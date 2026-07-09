<p align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.3"/>
  <img src="https://img.shields.io/badge/PostgreSQL-16-316192?logo=postgresql&logoColor=white" alt="PostgreSQL 16"/>
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License MIT"/>
</p>

# MesaQR API

API REST para la gestión digital de pedidos y pagos en restaurantes mediante códigos QR por mesa. Elimina la fricción entre el cliente y el mesero: el comensal escanea el QR de su mesa, arma su pedido desde el celular y paga sin esperar a nadie.

## Problema que resuelve

En un restaurante tradicional, el flujo cliente-mesero-cocina-caja tiene puntos de fricción constantes: el mesero tarda en llegar, anota mal un pedido, el cliente espera la cuenta. Cada espera innecesaria reduce la rotación de mesas y el ticket promedio.

**MesaQR** digitaliza ese flujo de punta a punta sobre una API robusta que un frontend móvil o web puede consumir directamente.

## Características principales

- **QR único por mesa** — cada mesa recibe un código QR con token de sesión de 24 h
- **Pedidos autogestionados** — el cliente agrega ítems al pedido activo de su mesa
- **Múltiples métodos de pago** — efectivo, tarjeta y transferencia QR
- **Tiempo real vía WebSocket** — notificaciones instantáneas cuando cambia el estado de una mesa o pedido
- **Control de concurrencia** — locks pesimistas (`PESSIMISTIC_WRITE`) + reintentos con Spring Retry para evitar condiciones de carrera en pedidos simultáneos
- **Tokens de sesión** — cada mesa tiene un token que expira cada 24 h; se regenera al liberar la mesa
- **Migraciones versionadas** — Flyway administra el esquema de base de datos de forma reproducible
- **Manejo centralizado de errores** — `@RestControllerAdvice` con códigos de error estructurados

## Stack tecnológico

| Capa | Tecnología |
|------|-----------|
| Lenguaje | Java 21 |
| Framework | Spring Boot 3.3 |
| Persistencia | Spring Data JPA + Hibernate |
| Base de datos | PostgreSQL 16 |
| Migraciones | Flyway |
| Tiempo real | WebSocket (STOMP sobre SockJS) |
| Códigos QR | ZXing |
| Vistas server-side | Thymeleaf |
| Concurrencia | Spring Retry + @Lock(PESSIMISTIC_WRITE) |
| Contenedores | Docker Compose |

## Requisitos previos

- **JDK 21**
- **Maven 3.9+** (incluye wrapper `mvnw`)
- **Docker** (para PostgreSQL local)
- **Postman / cURL** (para probar la API)

## Arranque rápido

### 1. Levantar PostgreSQL

```bash
docker compose up -d
```

Esto crea un contenedor `restaurant-db` con la base `restaurant_db`, usuario `restaurant` y contraseña `restaurant` en el puerto `5432`. Flyway ejecuta las migraciones automáticamente al iniciar la aplicación.

### 2. Ejecutar la aplicación

```bash
./mvnw spring-boot:run
```

La API estará disponible en `http://localhost:8080`.

### 3. Verificar

```bash
curl http://localhost:8080/api/mesas
```

## Flujo de uso

```
1. POST /api/mesas          → El restaurante crea una mesa (recibe QR + token)
2. GET  /api/mesas/{id}/qr  → La mesa muestra el QR al cliente
3. El cliente escanea       → Redirige a /menu/{mesaId}?token=...
4. POST /api/pedidos/mesa/{mesaId}       → Se abre un pedido para la mesa
5. POST /api/pedidos/mesa/{mesaId}/items → El cliente agrega platillos
6. POST /api/pagos          → El cliente paga (efectivo, tarjeta o QR)
7. WebSocket notifica       → /topic/mesas emite el cambio de estado
```

## API Reference

### Mesas

| Método | Endpoint | Descripción | Autenticación |
|--------|----------|-------------|------|
| `POST` | `/api/mesas` | Crear una mesa | — |
| `GET` | `/api/mesas` | Listar todas las mesas | — |
| `GET` | `/api/mesas/{id}` | Obtener mesa por ID | — |
| `GET` | `/api/mesas/{id}/qr` | Obtener QR de la mesa (PNG) | — |
| `PUT` | `/api/mesas/{id}/reservar` | Reservar una mesa disponible | — |
| `PUT` | `/api/mesas/{id}/liberar` | Liberar una mesa reservada | — |

**`POST /api/mesas`**

```json
{
  "numeroDeMesa": 7,
  "capacidad": 4
}
```

Respuesta `201`:

```json
{
  "id": 1,
  "numeroDeMesa": 7,
  "capacidad": 4,
  "estado": "DISPONIBLE",
  "urlQr": "http://localhost:8080/api/mesas/1/qr"
}
```

### Pedidos

| Método | Endpoint | Descripción | Autenticación |
|--------|----------|-------------|------|
| `POST` | `/api/pedidos/mesa/{mesaId}` | Abrir pedido para una mesa | `X-Session-Token` |
| `POST` | `/api/pedidos/mesa/{mesaId}/items` | Agregar elemento al pedido | `X-Session-Token` |
| `GET` | `/api/pedidos/mesa/{mesaId}` | Ver resumen del pedido activo | `X-Session-Token` |
| `PUT` | `/api/pedidos/mesa/{mesaId}/cancelar` | Cancelar pedido activo | `X-Session-Token` |

**`POST /api/pedidos/mesa/1/items`**

```json
{
  "platilloId": 1,
  "cantidad": 2,
  "notas": "sin cebolla"
}
```

Respuesta `201` (vacía). El resumen actualizado se emite por WebSocket.

**`GET /api/pedidos/mesa/1`**

```json
{
  "pedidoId": 1,
  "numeroDeMesa": 7,
  "detalles": [
    {
      "id": 1,
      "nombrePlatillo": "Hamburguesa clásica",
      "cantidad": 2,
      "precio": 14000,
      "subTotal": 28000,
      "notas": "sin cebolla"
    }
  ],
  "subtotal": 28000,
  "iva": 4480,
  "propina": 0,
  "total": 32480,
  "estado": "ABIERTO"
}
```

### Pagos

| Método | Endpoint | Descripción | Autenticación |
|--------|----------|-------------|------|
| `POST` | `/api/pagos/mesa/{mesaId}` | Procesar un pago | `X-Session-Token` |
| `POST` | `/api/pagos/{pagoId}/confirmar` | Confirmar pago QR pendiente | — |

**`POST /api/pagos/mesa/1`**

```json
{
  "metodo": "TARJETA",
  "tokenProveedor": "tok_visa_4242"
}
```

```json
{
  "pagoId": 1,
  "estado": "COMPLETADO",
  "subtotal": 28000,
  "iva": 4480,
  "propina": 0,
  "monto": 32480,
  "mensaje": "Pago con tarjeta procesado"
}
```

### Métodos de pago soportados

| Método | Enum | Flujo |
|--------|------|-------|
| Efectivo | `EFECTIVO` | Se marca completado instantáneamente |
| Tarjeta | `TARJETA` | Requiere `tokenProveedor`; se marca completado |
| Transferencia QR | `TRANSFERENCIA_QR` | Queda `PENDIENTE`; se confirma con endpoint aparte |

### Errores

Todos los errores siguen el formato:

```json
{
  "codigo": "NO_ENCONTRADO",
  "mensaje": "Mesa no encontrada: 99"
}
```

| Código | HTTP | Causa |
|--------|------|-------|
| `SOLICITUD_INVALIDA` | 400 | Datos inválidos (ej. número de mesa duplicado) |
| `ACCESO_DENEGADO` | 403 | Token de sesión inválido o expirado |
| `NO_ENCONTRADO` | 404 | Recurso no existe |
| `CONFLICTO` | 409 | Pedido ya pagado |
| `ERROR_INTERNO` | 500 | Error inesperado del servidor |
| `NO_SOPORTADO` | 400 | Método de pago no soportado |
| `ERROR_QR` | 500 | Error al generar código QR |
| `VALIDACION` | 400 | Error de validación en los datos enviados |

## WebSocket

| Propiedad | Valor |
|-----------|-------|
| Endpoint | `/ws` (SockJS) |
| Broker | simple (`/topic`) |
| Application prefix | `/app` |
| Tópico de mesas | `/topic/mesas` |

### Eventos emitidos

**`EventoCambioEstadoMesa`** — cuando una mesa cambia de estado (`OCUPADA` → `DISPONIBLE` tras pago, etc.):

```json
{
  "mesaId": 1,
  "numeroDeMesa": 7,
  "nuevoEstado": "DISPONIBLE"
}
```

**`EventoActualizacionPedido`** — cuando se agrega un ítem:

```json
{
  "mesaId": 1,
  "numeroDeMesa": 7,
  "pedidoId": 1,
  "total": 37000,
  "cantidadItems": 3
}
```

## Modelo de datos

```
mesas                 platillos             pedidos
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ id           │     │ id           │     │ id           │
│ numero_mesa  │     │ nombre       │     │ mesa_id (FK) │
│ capacidad    │     │ descripcion  │     │ estado       │
│ estado       │     │ precio       │     │ creado_en    │
│ codigo_qr    │     │ categoria    │     │ pagado_en    │
│ token_sesion │     │ disponible   │     │ version      │
│ token_expira │     │ url_imagen   │     └──────┬───────┘
└──────────────┘     └──────────────┘            │
                                                  │
                  detalles_pedido                 │
                 ┌──────────────────┐            │
                 │ id               │            │
                 │ pedido_id (FK)   ├────────────┘
                 │ platillo_id (FK) │
                 │ cantidad         │       pagos
                 │ precio           │     ┌──────────────────┐
                 │ notas            │     │ id               │
                 └──────────────────┘     │ pedido_id (FK)   │
                                          │ metodo           │
                                          │ estado           │
                                          │ monto            │
                                          │ referencia_prov  │
                                          │ creado_en        │
                                          └──────────────────┘
```

## Estructura del proyecto

```
src/main/java/com/restaurant/
├── AplicacionMesaQR.java                # Entry point
├── modelo/
│   ├── Mesa.java                        # Entidad mesa
│   ├── Platillo.java                    # Entidad platillo/menú
│   ├── Pedido.java                      # Entidad pedido (con @Version)
│   ├── DetallePedido.java               # Entidad línea de pedido
│   ├── Pago.java                        # Entidad pago
│   ├── EstadoMesa.java                  # Enum: DISPONIBLE, OCUPADA, RESERVADA
│   ├── EstadoPedido.java                # Enum: ABIERTO, PAGADO, CANCELADO
│   ├── EstadoPago.java                  # Enum: PENDIENTE, COMPLETADO, FALLIDO
│   └── MetodoPago.java                  # Enum: EFECTIVO, TARJETA, TRANSFERENCIA_QR
├── repositorio/
│   ├── MesaRepositorio.java             # JPARepository + lock pesimista
│   ├── PlatilloRepositorio.java
│   ├── PedidoRepositorio.java           # JPQL nativo para total + lock
│   ├── DetallePedidoRepositorio.java
│   └── PagoRepositorio.java
├── servicio/
│   ├── ServicioMesa.java                # CRUD mesas + tokens de sesión
│   ├── ServicioPedido.java              # Pedidos + @Retryable con backoff
│   ├── ServicioPago.java                # Procesamiento de pagos (strategy)
│   ├── ServicioQR.java                  # Generación QR con ZXing
│   └── ServicioPlatillo.java            # Catálogo de platillos
├── controlador/
│   ├── ControladorMesa.java             # /api/mesas
│   ├── ControladorPedido.java           # /api/pedidos
│   ├── ControladorPago.java             # /api/pagos
│   ├── ControladorQR.java               # /api/mesas/{id}/qr
│   └── ControladorVistaMenu.java        # Vista Thymeleaf /menu
├── dto/
│   ├── SolicitudCrearMesa.java
│   ├── RespuestaMesa.java
│   ├── SolicitudAgregarItem.java
│   ├── DetalleItemDTO.java
│   ├── ResumenPedidoDTO.java
│   ├── SolicitudPagoDTO.java
│   ├── RespuestaPagoDTO.java
│   ├── RespuestaError.java
│   └── eventos/
│       ├── EventoMesa.java
│       ├── EventoActualizacionPedido.java
│       └── EventoCambioEstadoMesa.java
├── configuracion/
│   └── ConfiguracionWebSocket.java      # STOMP + SockJS
└── excepcion/
    ├── ManejadorGlobalExcepciones.java   # @RestControllerAdvice
    ├── TokenInvalidoException.java
    └── PedidoYaPagadoException.java
```

## Configuración

| Propiedad | Valor por defecto | Descripción |
|-----------|-------------------|-------------|
| `server.port` | `8080` | Puerto HTTP |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/restaurant_db` | JDBC URL |
| `spring.datasource.username` | `restaurant` | Usuario de BD |
| `spring.datasource.password` | `restaurant` | Contraseña de BD |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Flyway maneja el esquema |
| `restaurant.name` | `Mi Restaurante` | Nombre mostrado en el menú |
| `spring.websocket.max-text-message-size` | `8192` | Tamaño máximo de mensaje WebSocket |
| `restaurant.iva.habilitado` | `true` | Habilitar cálculo de IVA |
| `restaurant.iva.porcentaje` | `16` | Porcentaje de IVA |
| `restaurant.propina.habilitada` | `false` | Habilitar cálculo de propina sugerida |
| `restaurant.propina.porcentaje` | `10` | Porcentaje de propina |

## Decisiones técnicas

### ¿Por qué lock pesimista?

En un restaurante real, dos personas en la misma mesa pueden agregar ítems simultáneamente. El `@Lock(PESSIMISTIC_WRITE)` en `PedidoRepositorio.findActivoByMesaId` garantiza que solo una transacción modifique el pedido a la vez. Spring Retry (`@Retryable`) reintenta hasta 3 veces con 100 ms de backoff si el lock falla.

### ¿Por qué tokens de sesión?

Cada mesa recibe un `UUID` como token al crearse. El token se envía en el header `X-Session-Token` y se valida en cada request. Así evitamos que un cliente modifique el pedido de otra mesa. El token expira a las 24 h y se regenera al liberar la mesa.

### ¿Por qué Flyway?

Las migraciones versionadas (`V1` a `V5`) garantizan que cualquier entorno (dev, staging, producción) tenga exactamente el mismo esquema. No hay sorpresas con `ddl-auto=update`.

## Roadmap

- [ ] Integración con pasarela de pago real (Stripe/MercadoPago)
- [ ] Webhooks para confirmación asíncrona de pagos QR
- [ ] Panel de administración (dashboard de mesas ocupadas, ventas del día)
- [ ] Notificaciones push a cocina (WebSocket a tópico `/topic/cocina`)
- [ ] Autenticación JWT para administradores del restaurante
- [ ] Tests de integración con Testcontainers
- [ ] Métricas con Micrometer + Prometheus

## Contribuir

1. Haz fork del repositorio
2. Crea un branch: `git checkout -b feature/nombre`
3. Haz commit de tus cambios: `git commit -m 'feat: descripción'`
4. Push al branch: `git push origin feature/nombre`
5. Abre un Pull Request

## Licencia

MIT © 2025 Sebastian Montes Olivera
