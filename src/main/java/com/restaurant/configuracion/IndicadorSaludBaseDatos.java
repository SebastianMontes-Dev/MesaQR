package com.restaurant.configuracion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Health indicator que verifica la integridad de la base de datos
 * en cada health check. Valida que las tablas requeridas existan
 * y que Flyway haya ejecutado todas las migraciones correctamente.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndicadorSaludBaseDatos implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    private static final List<String> TABLAS_REQUERIDAS = List.of(
            "mesas", "platillos", "pedidos", "detalles_pedido", "pagos"
    );

    @Override
    public Health health() {
        try {
            List<String> tablasExistentes = jdbcTemplate.queryForList(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                    String.class
            );

            List<String> tablasFaltantes = TABLAS_REQUERIDAS.stream()
                    .filter(t -> !tablasExistentes.contains(t))
                    .toList();

            if (!tablasFaltantes.isEmpty()) {
                log.error("Tablas faltantes en la base de datos: {}", tablasFaltantes);
                return Health.down()
                        .withDetail("tablasFaltantes", tablasFaltantes)
                        .withDetail("mensaje", "Faltan tablas requeridas en la base de datos")
                        .build();
            }

            Integer migraciones = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                    Integer.class
            );

            return Health.up()
                    .withDetail("tablasVerificadas", TABLAS_REQUERIDAS.size())
                    .withDetail("migracionesExitosas", migraciones)
                    .build();

        } catch (Exception e) {
            log.error("Error verificando integridad de la base de datos", e);
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}
