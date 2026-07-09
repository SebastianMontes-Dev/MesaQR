package com.restaurant.servicio;

import com.restaurant.dto.ResumenPedidoDTO;
import com.restaurant.excepcion.PedidoYaPagadoException;
import com.restaurant.excepcion.RecursoNoEncontradoException;
import com.restaurant.modelo.*;
import com.restaurant.repositorio.PedidoRepositorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicioPedido — Tests unitarios")
class ServicioPedidoTest {

    @Mock private PedidoRepositorio pedidoRepositorio;
    @Mock private ServicioPlatillo servicioPlatillo;
    @Mock private ServicioMesa servicioMesa;
    @Mock private SimpMessagingTemplate mensajeria;
    @InjectMocks private ServicioPedido servicioPedido;

    private Mesa mesaTest;
    private Pedido pedidoTest;
    private Platillo platilloTest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(servicioPedido, "ivaHabilitado", true);
        ReflectionTestUtils.setField(servicioPedido, "ivaPorcentaje", 16);
        ReflectionTestUtils.setField(servicioPedido, "propinaHabilitada", false);
        ReflectionTestUtils.setField(servicioPedido, "propinaPorcentaje", 10);

        mesaTest = new Mesa();
        mesaTest.setId(1L);
        mesaTest.setNumeroDeMesa(5);
        mesaTest.setEstado(EstadoMesa.OCUPADA);

        pedidoTest = new Pedido();
        pedidoTest.setId(1L);
        pedidoTest.setMesa(mesaTest);
        pedidoTest.setEstado(EstadoPedido.ABIERTO);
        pedidoTest.setDetalles(new ArrayList<>());
        pedidoTest.setCreadoEn(LocalDateTime.now());

        platilloTest = new Platillo();
        platilloTest.setId(1L);
        platilloTest.setNombre("Hamburguesa");
        platilloTest.setPrecio(BigDecimal.valueOf(14000));
    }

    @Test
    @DisplayName("Crear pedido exitosamente")
    void crearPedido_exitoso() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.empty());
        when(servicioMesa.buscarPorId(1L)).thenReturn(mesaTest);
        when(pedidoRepositorio.save(any(Pedido.class))).thenAnswer(inv -> {
            Pedido p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });

        mesaTest.setEstado(EstadoMesa.DISPONIBLE);
        Pedido resultado = servicioPedido.crearPedidoParaMesa(1L);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPedido.ABIERTO);
        verify(servicioMesa).actualizarEstado(1L, EstadoMesa.OCUPADA);
    }

    @Test
    @DisplayName("Crear pedido duplicado lanza excepción")
    void crearPedido_yaTienePedidoAbierto() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.of(pedidoTest));

        assertThatThrownBy(() -> servicioPedido.crearPedidoParaMesa(1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya tiene un pedido ABIERTO");
    }

    @Test
    @DisplayName("Agregar elemento al pedido exitosamente")
    void agregarElemento_exitoso() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.of(pedidoTest));
        when(servicioPlatillo.buscarPorId(1L)).thenReturn(platilloTest);
        when(pedidoRepositorio.save(any())).thenReturn(pedidoTest);
        when(pedidoRepositorio.getTotalPedido(1L)).thenReturn(BigDecimal.valueOf(14000));

        servicioPedido.agregarElemento(1L, 1L, 2, "sin cebolla");

        assertThat(pedidoTest.getDetalles()).hasSize(1);
        verify(mensajeria).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("Obtener pedido activo exitosamente")
    void obtenerPedidoActivo_exitoso() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.of(pedidoTest));

        Pedido resultado = servicioPedido.obtenerPedidoActivo(1L);
        assertThat(resultado.getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Obtener pedido inexistente lanza RecursoNoEncontradoException")
    void obtenerPedidoActivo_noExiste() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.empty());
        when(pedidoRepositorio.findFirstByMesaIdOrderByIdDesc(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicioPedido.obtenerPedidoActivo(1L))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Obtener pedido ya pagado lanza PedidoYaPagadoException")
    void obtenerPedidoActivo_yaPagado() {
        pedidoTest.setEstado(EstadoPedido.PAGADO);
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.empty());
        when(pedidoRepositorio.findFirstByMesaIdOrderByIdDesc(1L)).thenReturn(Optional.of(pedidoTest));

        assertThatThrownBy(() -> servicioPedido.obtenerPedidoActivo(1L))
                .isInstanceOf(PedidoYaPagadoException.class);
    }

    @Test
    @DisplayName("Cancelar pedido exitosamente")
    void cancelarPedido_exitoso() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.of(pedidoTest));

        servicioPedido.cancelarPedido(1L);

        assertThat(pedidoTest.getEstado()).isEqualTo(EstadoPedido.CANCELADO);
        verify(servicioMesa).actualizarEstado(1L, EstadoMesa.DISPONIBLE);
        verify(mensajeria).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("Marcar pedido como pagado exitosamente")
    void marcarComoPagado_exitoso() {
        when(pedidoRepositorio.findActivoByMesaId(1L)).thenReturn(Optional.of(pedidoTest));
        when(pedidoRepositorio.save(any())).thenReturn(pedidoTest);
        when(pedidoRepositorio.getTotalPedido(1L)).thenReturn(BigDecimal.valueOf(14000));

        Pedido resultado = servicioPedido.marcarComoPagado(1L);

        assertThat(resultado.getEstado()).isEqualTo(EstadoPedido.PAGADO);
        assertThat(resultado.getPagadoEn()).isNotNull();
        verify(servicioMesa).actualizarEstado(1L, EstadoMesa.DISPONIBLE);
    }

    @Test
    @DisplayName("Calcular IVA habilitado correctamente")
    void calcularIva_habilitado() {
        BigDecimal iva = servicioPedido.calcularIva(BigDecimal.valueOf(10000));
        assertThat(iva).isEqualByComparingTo(BigDecimal.valueOf(1600));
    }

    @Test
    @DisplayName("Calcular IVA deshabilitado retorna cero")
    void calcularIva_deshabilitado() {
        ReflectionTestUtils.setField(servicioPedido, "ivaHabilitado", false);
        BigDecimal iva = servicioPedido.calcularIva(BigDecimal.valueOf(10000));
        assertThat(iva).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("Calcular propina habilitada correctamente")
    void calcularPropina_habilitada() {
        ReflectionTestUtils.setField(servicioPedido, "propinaHabilitada", true);
        BigDecimal propina = servicioPedido.calcularPropina(BigDecimal.valueOf(10000));
        assertThat(propina).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    @DisplayName("Calcular total con impuestos (IVA + propina)")
    void calcularTotalConImpuestos() {
        ReflectionTestUtils.setField(servicioPedido, "propinaHabilitada", true);
        BigDecimal total = servicioPedido.calcularTotalConImpuestos(BigDecimal.valueOf(10000));
        // 10000 + 1600 (IVA 16%) + 1000 (propina 10%) = 12600
        assertThat(total).isEqualByComparingTo(BigDecimal.valueOf(12600));
    }
}
