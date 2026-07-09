package com.restaurant.servicio;

import com.restaurant.dto.RespuestaPagoDTO;
import com.restaurant.dto.SolicitudPagoDTO;
import com.restaurant.modelo.EstadoPago;
import com.restaurant.modelo.Pago;
import com.restaurant.modelo.Pedido;
import com.restaurant.repositorio.PagoRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.restaurant.excepcion.RecursoNoEncontradoException;
import com.restaurant.modelo.MetodoPago;
import java.math.BigDecimal;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioPago {

    private final PagoRepositorio pagoRepositorio;
    private final ServicioPedido servicioPedido;

    @org.springframework.beans.factory.annotation.Value("${restaurant.webhook.secret:secreto_default}")
    private String webhookSecret;

    @Transactional
    public RespuestaPagoDTO procesarPago(Long mesaId, SolicitudPagoDTO solicitud) {
        Pedido pedido = servicioPedido.obtenerPedidoActivo(mesaId);
        BigDecimal subtotal = servicioPedido.obtenerTotalPedido(pedido.getId());
        BigDecimal iva = servicioPedido.calcularIva(subtotal);
        BigDecimal propina = servicioPedido.calcularPropina(subtotal);
        BigDecimal total = servicioPedido.calcularTotalConImpuestos(subtotal);

        Pago pago = new Pago();
        pago.setPedido(pedido);
        pago.setMetodo(solicitud.getMetodo());
        pago.setMonto(total);

        return switch (solicitud.getMetodo()) {
            case EFECTIVO -> procesarPagoEfectivo(pago, pedido, subtotal, iva, propina);
            case TARJETA -> procesarPagoTarjeta(pago, pedido, solicitud.getTokenProveedor(), subtotal, iva, propina);
            case TRANSFERENCIA_QR -> procesarPagoTransferenciaQR(pago, pedido, subtotal, iva, propina);
            default -> throw new UnsupportedOperationException("Metodo de pago no soportado: " + solicitud.getMetodo());
        };
    }

    private RespuestaPagoDTO procesarPagoEfectivo(Pago pago, Pedido pedido,
                                                   BigDecimal subtotal, BigDecimal iva, BigDecimal propina) {
        pago.setEstado(EstadoPago.PENDIENTE);
        pagoRepositorio.save(pago);

        log.info("Pago en efectivo solicitado: mesa {} - ${}",
                pedido.getMesa().getNumeroDeMesa(), pago.getMonto());

        return RespuestaPagoDTO.builder()
                .pagoId(pago.getId())
                .estado(EstadoPago.PENDIENTE)
                .subtotal(subtotal)
                .iva(iva)
                .propina(propina)
                .monto(pago.getMonto())
                .mensaje("Pago en efectivo solicitado, a la espera de confirmación")
                .build();
    }

    private RespuestaPagoDTO procesarPagoTarjeta(Pago pago, Pedido pedido, String tokenProveedor,
                                                  BigDecimal subtotal, BigDecimal iva, BigDecimal propina) {
        pago.setReferenciaProveedor(tokenProveedor);
        pago.setEstado(EstadoPago.COMPLETADO);
        pagoRepositorio.save(pago);

        servicioPedido.marcarComoPagado(pedido.getMesa().getId());

        log.info("Pago con tarjeta procesado: mesa {} - ${}",
                pedido.getMesa().getNumeroDeMesa(), pago.getMonto());

        return RespuestaPagoDTO.builder()
                .pagoId(pago.getId())
                .estado(EstadoPago.COMPLETADO)
                .subtotal(subtotal)
                .iva(iva)
                .propina(propina)
                .monto(pago.getMonto())
                .mensaje("Pago con tarjeta procesado")
                .build();
    }

    private RespuestaPagoDTO procesarPagoTransferenciaQR(Pago pago, Pedido pedido,
                                                          BigDecimal subtotal, BigDecimal iva, BigDecimal propina) {
        pago.setEstado(EstadoPago.PENDIENTE);
        pagoRepositorio.save(pago);

        return RespuestaPagoDTO.builder()
                .pagoId(pago.getId())
                .estado(EstadoPago.PENDIENTE)
                .subtotal(subtotal)
                .iva(iva)
                .propina(propina)
                .monto(pago.getMonto())
                .mensaje("Realiza la transferencia QR")
                .urlRedireccion("/api/pagos/" + pago.getId() + "/confirmar")
                .build();
    }



    @Transactional
    public void manejarNotificacionExterna(String payload, String firma) {
        log.info("Notificación externa recibida: firma={}", firma);
        
        if (firma == null || firma.isEmpty()) {
            throw new IllegalArgumentException("Firma de webhook faltante");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            String firmaCalculada = sb.toString();

            if (!firmaCalculada.equalsIgnoreCase(firma)) {
                log.error("Firma de webhook inválida. Calculada: {}, Recibida: {}", firmaCalculada, firma);
                throw new IllegalArgumentException("Firma de webhook inválida");
            }
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Error al calcular HMAC", e);
        }

        log.debug("Payload validado exitosamente: {}", payload);
    }

    @Transactional
    public RespuestaPagoDTO confirmarPagoQR(Long pagoId) {
        Pago pago = pagoRepositorio.findById(pagoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + pagoId));

        if (pago.getMetodo() != MetodoPago.TRANSFERENCIA_QR) {
            throw new IllegalArgumentException("Este pago no es de tipo transferencia QR");
        }

        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            throw new IllegalArgumentException("El pago ya fue procesado o no está pendiente");
        }

        pago.setEstado(EstadoPago.COMPLETADO);
        pagoRepositorio.save(pago);

        servicioPedido.marcarComoPagado(pago.getPedido().getMesa().getId());

        log.info("Pago QR confirmado: ${}", pago.getMonto());

        return RespuestaPagoDTO.builder()
                .pagoId(pago.getId())
                .estado(EstadoPago.COMPLETADO)
                .monto(pago.getMonto())
                .mensaje("Pago confirmado exitosamente")
                .build();
    }

    @Transactional
    public RespuestaPagoDTO confirmarPagoEfectivo(Long pagoId) {
        Pago pago = pagoRepositorio.findById(pagoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Pago no encontrado: " + pagoId));

        if (pago.getMetodo() != MetodoPago.EFECTIVO) {
            throw new IllegalArgumentException("El pago no es en efectivo");
        }

        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            throw new IllegalArgumentException("El pago ya fue procesado o no está pendiente");
        }

        pago.setEstado(EstadoPago.COMPLETADO);
        pagoRepositorio.save(pago);

        servicioPedido.marcarComoPagado(pago.getPedido().getMesa().getId());

        log.info("Pago en efectivo confirmado: ${}", pago.getMonto());

        return RespuestaPagoDTO.builder()
                .pagoId(pago.getId())
                .estado(EstadoPago.COMPLETADO)
                .monto(pago.getMonto())
                .mensaje("Pago en efectivo confirmado exitosamente")
                .build();
    }
}
