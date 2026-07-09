package com.restaurant.controlador;

import com.restaurant.dto.RespuestaPagoDTO;
import com.restaurant.dto.SolicitudPagoDTO;
import com.restaurant.servicio.ServicioMesa;
import com.restaurant.servicio.ServicioPago;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/api/pagos")
@RequiredArgsConstructor
@RateLimiter(name = "pago")
public class ControladorPago {

    private final ServicioPago servicioPago;
    private final ServicioMesa servicioMesa;

    @PostMapping("/mesa/{mesaId}")
    public ResponseEntity<RespuestaPagoDTO> pagar(
            @PathVariable Long mesaId,
            @RequestHeader("X-Session-Token") String token,
            @Valid @RequestBody SolicitudPagoDTO solicitud) {

        Long mesaIdFinal = solicitud.getMesaId() != null ? solicitud.getMesaId() : mesaId;
        servicioMesa.validarToken(mesaIdFinal, token);
        return ResponseEntity.ok(servicioPago.procesarPago(mesaIdFinal, solicitud));
    }

    @PostMapping("/notificacion-externa")
    public ResponseEntity<Void> notificacionExterna(
            @RequestBody String payload,
            @RequestHeader(value = "Firma-Externa", required = false) String firma) {

        servicioPago.manejarNotificacionExterna(payload, firma);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{pagoId}/confirmar")
    public ResponseEntity<RespuestaPagoDTO> confirmarPagoQR(@PathVariable Long pagoId) {
        return ResponseEntity.ok(servicioPago.confirmarPagoQR(pagoId));
    }

    @PostMapping("/{pagoId}/confirmar-efectivo")
    public ResponseEntity<RespuestaPagoDTO> confirmarPagoEfectivo(@PathVariable Long pagoId) {
        return ResponseEntity.ok(servicioPago.confirmarPagoEfectivo(pagoId));
    }
}
