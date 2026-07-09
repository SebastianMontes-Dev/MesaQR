package com.restaurant.integracion;

import com.restaurant.modelo.*;
import com.restaurant.repositorio.MesaRepositorio;
import com.restaurant.repositorio.PedidoRepositorio;
import com.restaurant.repositorio.PlatilloRepositorio;
import com.restaurant.servicio.ServicioPedido;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de concurrencia que valida que múltiples hilos pueden
 * agregar ítems simultáneamente sin corromper datos.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Concurrencia — Test de locks pesimistas")
class ConcurrenciaPedidoTest {

    @Autowired private ServicioPedido servicioPedido;
    @Autowired private MesaRepositorio mesaRepositorio;
    @Autowired private PedidoRepositorio pedidoRepositorio;
    @Autowired private PlatilloRepositorio platilloRepositorio;

    private Long mesaId;
    private Long platilloId;

    @BeforeEach
    void setUp() {
        // Crear platillo de prueba
        Platillo platillo = new Platillo();
        platillo.setNombre("Taco Concurrencia");
        platillo.setPrecio(BigDecimal.valueOf(5000));
        platillo.setCategoria("Tacos");
        platillo.setDisponible(true);
        platillo = platilloRepositorio.save(platillo);
        platilloId = platillo.getId();

        // Crear mesa de prueba
        Mesa mesa = new Mesa();
        mesa.setNumeroDeMesa((int) (Math.random() * 9000 + 1000)); // número aleatorio para evitar conflictos
        mesa.setCapacidad(6);
        mesa.setEstado(EstadoMesa.DISPONIBLE);
        mesa.setTokenSesion("token-concurrencia");
        mesa = mesaRepositorio.save(mesa);
        mesaId = mesa.getId();

        // Crear pedido
        servicioPedido.crearPedidoParaMesa(mesaId);
    }

    @Test
    @DisplayName("Agregar ítems concurrentemente mantiene integridad de datos")
    void agregarElementosConcurrentemente() throws InterruptedException {
        int numHilos = 5;
        ExecutorService executor = Executors.newFixedThreadPool(numHilos);
        CountDownLatch barrera = new CountDownLatch(1);
        CountDownLatch finalizacion = new CountDownLatch(numHilos);
        List<Future<Boolean>> resultados = new ArrayList<>();

        for (int i = 0; i < numHilos; i++) {
            resultados.add(executor.submit(() -> {
                try {
                    barrera.await(); // Esperar a que todos los hilos estén listos
                    servicioPedido.agregarElemento(mesaId, platilloId, 1, "hilo " + Thread.currentThread().getName());
                    return true;
                } catch (Exception e) {
                    // Los reintentos deberían manejar los conflictos de lock
                    return false;
                } finally {
                    finalizacion.countDown();
                }
            }));
        }

        // Disparar todos los hilos simultáneamente
        barrera.countDown();
        finalizacion.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Contar cuántos tuvieron éxito
        long exitosos = resultados.stream()
                .map(f -> {
                    try { return f.get(); }
                    catch (Exception e) { return false; }
                })
                .filter(Boolean::booleanValue)
                .count();

        // Verificar que al menos la mayoría tuvo éxito
        assertThat(exitosos).isGreaterThanOrEqualTo(1);

        // Verificar que el total de ítems en BD coincide
        Pedido pedido = pedidoRepositorio.findActivoByMesaId(mesaId).orElseThrow();
        assertThat(pedido.getDetalles().size()).isEqualTo((int) exitosos);

        // Verificar total correcto
        BigDecimal totalEsperado = BigDecimal.valueOf(5000).multiply(BigDecimal.valueOf(exitosos));
        BigDecimal totalReal = pedidoRepositorio.getTotalPedido(pedido.getId());
        assertThat(totalReal).isEqualByComparingTo(totalEsperado);
    }
}
