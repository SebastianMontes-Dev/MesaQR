package com.restaurant.controlador;

import com.restaurant.dto.ResumenPedidoDTO;
import com.restaurant.dto.SolicitudAgregarElemento;
import com.restaurant.servicio.ServicioMesa;
import com.restaurant.servicio.ServicioPedido;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/api/pedidos")
@RequiredArgsConstructor
@RateLimiter(name = "pedido")
public class ControladorPedido {

    private final ServicioPedido servicioPedido;
    private final ServicioMesa servicioMesa;

    @GetMapping("/mesa/{mesaId}")
    public ResponseEntity<ResumenPedidoDTO> obtenerResumen(
            @PathVariable Long mesaId,
            @RequestHeader("X-Session-Token") String token) {

        servicioMesa.validarToken(mesaId, token);
        return ResponseEntity.ok(servicioPedido.obtenerResumenPedido(mesaId));
    }

    @PostMapping("/mesa/{mesaId}/items")
    public ResponseEntity<Void> agregarElemento(
            @PathVariable Long mesaId,
            @RequestHeader("X-Session-Token") String token,
            @RequestBody @Valid SolicitudAgregarElemento solicitud) {

        servicioMesa.validarToken(mesaId, token);
        servicioPedido.agregarElemento(mesaId, solicitud.getPlatilloId(), solicitud.getCantidad(), solicitud.getNotas());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/mesa/{mesaId}")
    public ResponseEntity<Void> crearPedido(
            @PathVariable Long mesaId,
            @RequestHeader("X-Session-Token") String token) {

        servicioMesa.validarToken(mesaId, token);
        servicioPedido.crearPedidoParaMesa(mesaId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PutMapping("/mesa/{mesaId}/cancelar")
    public ResponseEntity<Void> cancelarPedido(
            @PathVariable Long mesaId,
            @RequestHeader("X-Session-Token") String token) {

        servicioMesa.validarToken(mesaId, token);
        servicioPedido.cancelarPedido(mesaId);
        return ResponseEntity.ok().build();
    }
}
