<div align="center">
  <img src="https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 21"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.3.0-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="Spring Boot 3.3.0"/>
  <img src="https://img.shields.io/badge/PostgreSQL-16-316192?style=for-the-badge&logo=postgresql&logoColor=white" alt="PostgreSQL 16"/>
  <img src="https://img.shields.io/badge/WebSocket-STOMP-blue?style=for-the-badge&logo=socket.io" alt="WebSocket STOMP"/>
  <img src="https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white" alt="Docker"/>
  <br><br>
  <h1>🍽️ MesaQR API</h1>
  <p><strong>Plataforma Corporativa de Gestión y Autopedidos para Restaurantes</strong></p>
  <p>Elimina la fricción entre el cliente y el personal de servicio. Autogestión de pedidos en tiempo real mediante códigos QR.</p>
</div>

<br>

> **MesaQR** revoluciona la experiencia gastronómica mediante la digitalización del flujo tradicional `Cliente ↔ Personal de Servicio ↔ Cocina`. 

---

## 📋 Tabla de Contenidos
- [Visión General](#-visión-general)
- [Características Principales](#-características-principales)
- [Arquitectura de la Solución](#-arquitectura-de-la-solución)
- [Tecnologías Utilizadas](#-tecnologías-utilizadas)
- [Inicio Rápido](#-inicio-rápido)
- [Análisis Profundo: Control de Concurrencia](#-análisis-profundo-control-de-concurrencia)
- [Hoja de Ruta](#-hoja-de-ruta)
- [Licencia](#-licencia)

---

## 🚀 Visión General

Basado en una arquitectura modular robusta y orientada a eventos, **MesaQR** permite a los comensales escanear un código QR único por mesa, realizar pedidos de manera simultánea en tiempo real y pagar instantáneamente, reduciendo los tiempos de espera y aumentando la rotación de mesas.

Diseñado con los más altos estándares de la industria (nivel corporativo), garantiza alta disponibilidad, concurrencia segura mediante *Bloqueo Pesimista*, y capacidad de observación completa con métricas en tiempo real.

---

## ✨ Características Principales

- 📱 **Autogestión sin fricción:** Código QR único por sesión (tiempo de vida de 24 horas) inyectado dinámicamente para un acceso directo sin necesidad de registro.
- ⚡ **Sincronización en Tiempo Real:** Arquitectura orientada a eventos sobre conexiones bidireccionales (WebSockets) para actualizaciones al instante dirigidas al personal de servicio y la cocina.
- 🛡️ **Seguridad y Concurrencia:** Integración de bloqueos a nivel de base de datos (`PESSIMISTIC_WRITE`) y control de tolerancia a fallos para manejar colisiones de múltiples comensales realizando pedidos en la misma mesa.
- 💳 **Pagos Flexibles:** Pasarela versátil que admite pagos en efectivo, tarjeta (preparado para Stripe), y llamadas web (Webhooks) para confirmaciones de transferencias asíncronas.
- 📊 **Capacidad de Observación Integral:** Monitoreo y métricas en tiempo real de los servicios y estado de la aplicación.
- 🐳 **Despliegue Contenerizado:** Entorno multietapa en Docker, listo para ser orquestado y escalado en la nube.

---

## 🏗️ Arquitectura de la Solución

MesaQR utiliza un patrón de diseño monolítico modular, preparado para escalar a una infraestructura completa de microservicios en el futuro.

```mermaid
graph TD
    Client[📱 Cliente Web / Móvil] -->|HTTP / REST| API[🛡️ Interfaz de Programación - Spring Boot]
    Client -->|WebSocket / STOMP| WS[⚡ Servidor de Conexión en Tiempo Real]
    
    API --> |CRUD y Bloqueos| DB[(🐘 PostgreSQL 16)]
    API --> |Migraciones| Flyway[🗂️ Gestor de Base de Datos]
    
    API --> |Métricas| Prometheus[📈 Sistema de Monitoreo]
    
    subgraph Dominio Principal
        Mesa[Gestión de Mesas y Códigos QR]
        Pedido[Procesamiento de Pedidos]
        Pago[Pasarela de Pagos]
    end
    
    API -.-> Dominio Principal
```

---

## 🛠️ Tecnologías Utilizadas

| Categoría | Tecnologías |
| :--- | :--- |
| **Núcleo Lógico** | Java 21, Spring Boot 3.3.0 |
| **Base de Datos** | PostgreSQL 16, H2 (Pruebas), Flyway (Versionado) |
| **Comunicación** | API REST, WebSocket (STOMP) |
| **Resiliencia y Monitoreo** | Resilience4j, Spring Boot Actuator, Micrometer (Prometheus) |
| **Infraestructura** | Docker, Docker Compose, Maven Wrapper |
| **Pruebas** | JUnit 5, Mockito, Testcontainers |

---

## ⚙️ Inicio Rápido

El proyecto está preparado para funcionar de inmediato mediante la orquestación de contenedores locales.

### 📋 Requisitos Previos
- **Docker y Docker Compose** (para la infraestructura de base de datos)
- **Kit de Desarrollo de Java (JDK) 21** (opcional, para desarrollo local sin contenedores)

### 🚀 Pasos de Ejecución

1. **Levantar la Infraestructura:**
   ```bash
   docker-compose up -d
   ```
   > *Nota: Esto iniciará la base de datos PostgreSQL en el puerto 5432 y ejecutará las migraciones iniciales automáticas.*

2. **Ejecutar la Aplicación Principal:**
   ```bash
   ./mvnw spring-boot:run
   ```
   > *La interfaz de programación (API) estará disponible en `http://localhost:8080` y la documentación interactiva en `http://localhost:8080/swagger-ui.html`.*

---

## 🔒 Análisis Profundo: Control de Concurrencia

En un entorno de restaurante de alta demanda, múltiples clientes en la misma mesa pueden intentar modificar el mismo pedido simultáneamente. MesaQR soluciona este desafío técnico mediante:

1. **Bloqueo Pesimista:** Utilización de candados exclusivos de escritura en la capa de persistencia de datos, garantizando integridad referencial estricta.
2. **Reintentos Automáticos:** Implementación de retroceso exponencial (Exponential Backoff) para asegurar que ninguna petición se pierda debido a bloqueos temporales, garantizando una experiencia de usuario fluida y libre de errores.

---

## 🗺️ Hoja de Ruta

Para conocer los planes a futuro y el desarrollo planificado del proyecto, consulta nuestra [Hoja de Ruta (Roadmap)](ROADMAP.md).

---

## 📄 Licencia

Distribuido bajo la Licencia MIT. Ver el archivo `LICENSE` para más detalles.
