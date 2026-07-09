package com.restaurant.servicio;

import com.restaurant.dto.RespuestaPagoDTO;
import com.restaurant.dto.SolicitudPagoDTO;
import com.restaurant.excepcion.RecursoNoEncontradoException;
import com.restaurant.modelo.*;
import com.restaurant.repositorio.PagoRepositorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicioPago — Tests unitarios")
class ServicioPagoTest {

    @Mock private PagoRepositorio pagoRepositorio;
    @Mock private ServicioPedido servicioPedido;
    @InjectMocks private ServicioPago servicioPago;

    private Mesa mesaTest;
    private Pedido pedidoTest;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(servicioPago, "webhookSecret", "test_secret");

        mesaTest = new Mesa();
        mesaTest.setId(1L);
        mesaTest.setNumeroDeMesa(5);

        pedidoTest = new Pedido();
        pedidoTest.setId(1L);
        pedidoTest.setMesa(mesaTest);
        pedidoTest.setEstado(EstadoPedido.ABIERTO);
    }

    private void configurarMocksParaPago() {
        when(servicioPedido.obtenerPedidoActivo(1L)).thenReturn(pedidoTest);
        when(servicioPedido.obtenerTotalPedido(1L)).thenReturn(BigDecimal.valueOf(14000));
        when(servicioPedido.calcularIva(any())).thenReturn(BigDecimal.valueOf(2240));
        when(servicioPedido.calcularPropina(any())).thenReturn(BigDecimal.ZERO);
        when(servicioPedido.calcularTotalConImpuestos(any())).thenReturn(BigDecimal.valueOf(16240));
        when(pagoRepositorio.save(any(Pago.class))).thenAnswer(inv -> {
            Pago p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
    }

    @Test
    @DisplayName("Procesar pago en efectivo queda PENDIENTE")
    void procesarPago_efectivo() {
        configurarMocksParaPago();
        SolicitudPagoDTO solicitud = new SolicitudPagoDTO();
        solicitud.setMetodo(MetodoPago.EFECTIVO);

        RespuestaPagoDTO resp = servicioPago.procesarPago(1L, solicitud);

        assertThat(resp.getEstado()).isEqualTo(EstadoPago.PENDIENTE);
        assertThat(resp.getMonto()).isEqualByComparingTo(BigDecimal.valueOf(16240));
        verify(servicioPedido, never()).marcarComoPagado(anyLong());
    }

    @Test
    @DisplayName("Procesar pago con tarjeta queda COMPLETADO")
    void procesarPago_tarjeta() {
        configurarMocksParaPago();
        SolicitudPagoDTO solicitud = new SolicitudPagoDTO();
        solicitud.setMetodo(MetodoPago.TARJETA);
        solicitud.setTokenProveedor("tok_visa_4242");

        RespuestaPagoDTO resp = servicioPago.procesarPago(1L, solicitud);

        assertThat(resp.getEstado()).isEqualTo(EstadoPago.COMPLETADO);
        verify(servicioPedido).marcarComoPagado(1L);
    }

    @Test
    @DisplayName("Procesar pago QR queda PENDIENTE")
    void procesarPago_transferenciaQR() {
        configurarMocksParaPago();
        SolicitudPagoDTO solicitud = new SolicitudPagoDTO();
        solicitud.setMetodo(MetodoPago.TRANSFERENCIA_QR);

        RespuestaPagoDTO resp = servicioPago.procesarPago(1L, solicitud);

        assertThat(resp.getEstado()).isEqualTo(EstadoPago.PENDIENTE);
        assertThat(resp.getUrlRedireccion()).contains("/confirmar");
    }

    @Test
    @DisplayName("Confirmar pago QR exitosamente")
    void confirmarPagoQR_exitoso() {
        Pago pago = new Pago();
        pago.setId(1L);
        pago.setPedido(pedidoTest);
        pago.setMetodo(MetodoPago.TRANSFERENCIA_QR);
        pago.setEstado(EstadoPago.PENDIENTE);
        pago.setMonto(BigDecimal.valueOf(16240));
        when(pagoRepositorio.findById(1L)).thenReturn(Optional.of(pago));

        RespuestaPagoDTO resp = servicioPago.confirmarPagoQR(1L);

        assertThat(resp.getEstado()).isEqualTo(EstadoPago.COMPLETADO);
        verify(servicioPedido).marcarComoPagado(1L);
    }

    @Test
    @DisplayName("Confirmar pago QR no encontrado lanza excepción")
    void confirmarPagoQR_noEncontrado() {
        when(pagoRepositorio.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicioPago.confirmarPagoQR(99L))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Confirmar pago QR con tipo incorrecto lanza excepción")
    void confirmarPagoQR_tipoIncorrecto() {
        Pago pago = new Pago();
        pago.setId(1L);
        pago.setMetodo(MetodoPago.EFECTIVO);
        pago.setEstado(EstadoPago.PENDIENTE);
        when(pagoRepositorio.findById(1L)).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> servicioPago.confirmarPagoQR(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Confirmar pago QR ya completado lanza excepción")
    void confirmarPagoQR_yaCompletado() {
        Pago pago = new Pago();
        pago.setId(1L);
        pago.setMetodo(MetodoPago.TRANSFERENCIA_QR);
        pago.setEstado(EstadoPago.COMPLETADO);
        when(pagoRepositorio.findById(1L)).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> servicioPago.confirmarPagoQR(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Confirmar pago en efectivo exitosamente")
    void confirmarPagoEfectivo_exitoso() {
        Pago pago = new Pago();
        pago.setId(1L);
        pago.setPedido(pedidoTest);
        pago.setMetodo(MetodoPago.EFECTIVO);
        pago.setEstado(EstadoPago.PENDIENTE);
        pago.setMonto(BigDecimal.valueOf(16240));
        when(pagoRepositorio.findById(1L)).thenReturn(Optional.of(pago));

        RespuestaPagoDTO resp = servicioPago.confirmarPagoEfectivo(1L);

        assertThat(resp.getEstado()).isEqualTo(EstadoPago.COMPLETADO);
        verify(servicioPedido).marcarComoPagado(1L);
    }

    @Test
    @DisplayName("Confirmar pago en efectivo con tipo incorrecto lanza excepción")
    void confirmarPagoEfectivo_tipoIncorrecto() {
        Pago pago = new Pago();
        pago.setId(1L);
        pago.setMetodo(MetodoPago.TARJETA);
        pago.setEstado(EstadoPago.PENDIENTE);
        when(pagoRepositorio.findById(1L)).thenReturn(Optional.of(pago));

        assertThatThrownBy(() -> servicioPago.confirmarPagoEfectivo(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Webhook con firma válida no lanza excepción")
    void manejarNotificacionExterna_firmaValida() {
        // HMAC-SHA256 de "test_payload" con clave "test_secret"
        String payload = "test_payload";
        // Calculamos la firma esperada
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(
                    "test_secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(key);
            byte[] hmac = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hmac) sb.append(String.format("%02x", b));
            String firma = sb.toString();

            assertThatCode(() -> servicioPago.manejarNotificacionExterna(payload, firma))
                    .doesNotThrowAnyException();
        } catch (Exception e) {
            fail("Error calculando HMAC: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Webhook con firma inválida lanza excepción")
    void manejarNotificacionExterna_firmaInvalida() {
        assertThatThrownBy(() -> servicioPago.manejarNotificacionExterna("payload", "firma_falsa"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inválida");
    }

    @Test
    @DisplayName("Webhook sin firma lanza excepción")
    void manejarNotificacionExterna_sinFirma() {
        assertThatThrownBy(() -> servicioPago.manejarNotificacionExterna("payload", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("faltante");
    }
}
