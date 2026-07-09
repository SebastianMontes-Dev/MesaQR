package com.restaurant.servicio;

import com.restaurant.dto.DetalleElementoDTO;
import com.restaurant.dto.ResumenPedidoDTO;
import com.restaurant.dto.eventos.EventoActualizacionPedido;
import com.restaurant.dto.eventos.EventoCambioEstadoMesa;
import com.restaurant.modelo.*;
import com.restaurant.excepcion.PedidoYaPagadoException;
import com.restaurant.excepcion.RecursoNoEncontradoException;
import com.restaurant.repositorio.PedidoRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Servicio principal para la gestión de pedidos del restaurante.
 * Maneja la creación, modificación, cancelación y consulta de pedidos,
 * así como el cálculo de totales con IVA y propina.
 * Utiliza bloqueo pesimista para operaciones de escritura y consultas
 * sin bloqueo para operaciones de solo lectura.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioPedido {

    private final PedidoRepositorio pedidoRepositorio;
    private final ServicioPlatillo servicioPlatillo;
    private final ServicioMesa servicioMesa;
    private final SimpMessagingTemplate mensajeria;

    @Value("${restaurant.iva.habilitado:true}")
    private boolean ivaHabilitado;

    @Value("${restaurant.iva.porcentaje:16}")
    private int ivaPorcentaje;

    @Value("${restaurant.propina.habilitada:false}")
    private boolean propinaHabilitada;

    @Value("${restaurant.propina.porcentaje:10}")
    private int propinaPorcentaje;

    /**
     * Obtiene el resumen del pedido activo de una mesa, incluyendo
     * detalles de elementos, subtotal, IVA, propina y total.
     * Usa una consulta de solo lectura sin bloqueo pesimista.
     *
     * @param mesaId identificador de la mesa
     * @return resumen completo del pedido activo
     * @throws PedidoYaPagadoException   si el pedido ya fue pagado o cerrado
     * @throws RecursoNoEncontradoException si no existe pedido para la mesa
     */
    @Transactional(readOnly = true)
    public ResumenPedidoDTO obtenerResumenPedido(Long mesaId) {
        Pedido pedido = pedidoRepositorio.findActivoByMesaIdSoloLectura(mesaId)
                .orElseThrow(() -> {
                    Optional<Pedido> ultimo = pedidoRepositorio.findFirstByMesaIdOrderByIdDesc(mesaId);
                    if (ultimo.isPresent() && ultimo.get().getEstado() != EstadoPedido.ABIERTO) {
                        return new PedidoYaPagadoException("El pedido de la mesa " + mesaId + " ya fue pagado o cerrado");
                    }
                    return new RecursoNoEncontradoException("No existe pedido para la mesa " + mesaId);
                });
        return construirResumen(pedido);
    }

    /**
     * Agrega un elemento (platillo) al pedido activo de una mesa.
     * Utiliza reintentos automáticos en caso de conflictos de bloqueo.
     *
     * @param mesaId     identificador de la mesa
     * @param platilloId identificador del platillo a agregar
     * @param cantidad   cantidad de unidades
     * @param notas      notas especiales del cliente (puede ser null)
     */
    @Transactional
    @Retryable(retryFor = {PessimisticLockingFailureException.class, CannotAcquireLockException.class},
               maxAttemptsExpression = "${restaurant.retry.max-intentos:3}", backoff = @Backoff(delayExpression = "${restaurant.retry.delay-ms:100}"))
    public void agregarElemento(Long mesaId, Long platilloId, int cantidad, String notas) {
        Pedido pedido = obtenerPedidoActivo(mesaId);

        Platillo platillo = servicioPlatillo.buscarPorId(platilloId);

        DetallePedido detalle = new DetallePedido();
        detalle.setPedido(pedido);
        detalle.setPlatillo(platillo);
        detalle.setCantidad(cantidad);
        detalle.setPrecio(platillo.getPrecio());
        detalle.setNotas(notas);

        pedido.getDetalles().add(detalle);
        pedidoRepositorio.save(pedido);

        Mesa mesa = pedido.getMesa();
        BigDecimal total = calcularTotalConImpuestos(obtenerTotalPedido(pedido.getId()));

        EventoActualizacionPedido evento = new EventoActualizacionPedido(
                mesa.getId(), mesa.getNumeroDeMesa(), pedido.getId(), total, pedido.getDetalles().size()
        );
        mensajeria.convertAndSend("/topic/mesas", evento);

        log.info("Elemento agregado a mesa {}: {}x {} - total: {}",
                mesa.getNumeroDeMesa(), cantidad, platillo.getNombre(), total);
    }

    /**
     * Crea un nuevo pedido para una mesa específica.
     * Si la mesa está disponible o reservada, cambia su estado a ocupada.
     *
     * @param mesaId identificador de la mesa
     * @return el pedido recién creado
     * @throws IllegalArgumentException si la mesa ya tiene un pedido abierto
     */
    @Transactional
    public Pedido crearPedidoParaMesa(Long mesaId) {
        Optional<Pedido> existente = pedidoRepositorio.findActivoByMesaId(mesaId);
        if (existente.isPresent()) {
            throw new IllegalArgumentException("La mesa " + mesaId + " ya tiene un pedido ABIERTO");
        }

        Mesa mesa = servicioMesa.buscarPorId(mesaId);

        if (mesa.getEstado() == EstadoMesa.DISPONIBLE || mesa.getEstado() == EstadoMesa.RESERVADA) {
            servicioMesa.actualizarEstado(mesaId, EstadoMesa.OCUPADA);
        }

        Pedido pedido = new Pedido();
        pedido.setMesa(mesa);
        pedido.setEstado(EstadoPedido.ABIERTO);
        pedido.setCreadoEn(LocalDateTime.now());

        return pedidoRepositorio.save(pedido);
    }

    /**
     * Marca el pedido activo de una mesa como pagado.
     * Libera la mesa (estado DISPONIBLE) y notifica por WebSocket.
     *
     * @param mesaId identificador de la mesa
     * @return el pedido marcado como pagado
     */
    @Transactional
    public Pedido marcarComoPagado(Long mesaId) {
        Pedido pedido = obtenerPedidoActivo(mesaId);
        pedido.setEstado(EstadoPedido.PAGADO);
        pedido.setPagadoEn(LocalDateTime.now());
        pedidoRepositorio.save(pedido);

        servicioMesa.actualizarEstado(mesaId, EstadoMesa.DISPONIBLE);

        EventoCambioEstadoMesa evento = new EventoCambioEstadoMesa(
                mesaId, pedido.getMesa().getNumeroDeMesa(), EstadoMesa.DISPONIBLE
        );
        mensajeria.convertAndSend("/topic/mesas", evento);

        log.info("Pedido pagado: mesa {} - total: {}",
                pedido.getMesa().getNumeroDeMesa(), obtenerTotalPedido(pedido.getId()));
        return pedido;
    }

    /**
     * Cancela el pedido activo de una mesa.
     * Libera la mesa (estado DISPONIBLE) y notifica por WebSocket.
     *
     * @param mesaId identificador de la mesa
     */
    @Transactional
    public void cancelarPedido(Long mesaId) {
        Pedido pedido = obtenerPedidoActivo(mesaId);
        pedido.setEstado(EstadoPedido.CANCELADO);
        pedidoRepositorio.save(pedido);

        servicioMesa.actualizarEstado(mesaId, EstadoMesa.DISPONIBLE);

        EventoCambioEstadoMesa evento = new EventoCambioEstadoMesa(
                mesaId, pedido.getMesa().getNumeroDeMesa(), EstadoMesa.DISPONIBLE
        );
        mensajeria.convertAndSend("/topic/mesas", evento);

        log.info("Pedido cancelado: mesa {}", pedido.getMesa().getNumeroDeMesa());
    }

    /**
     * Obtiene el pedido activo de una mesa usando bloqueo pesimista.
     * Adecuado para operaciones de escritura que requieren consistencia.
     *
     * @param mesaId identificador de la mesa
     * @return el pedido activo con bloqueo pesimista
     * @throws PedidoYaPagadoException   si el pedido ya fue pagado o cerrado
     * @throws RecursoNoEncontradoException si no existe pedido para la mesa
     */
    public Pedido obtenerPedidoActivo(Long mesaId) {
        return pedidoRepositorio.findActivoByMesaId(mesaId)
                .orElseThrow(() -> {
                    Optional<Pedido> ultimo = pedidoRepositorio.findFirstByMesaIdOrderByIdDesc(mesaId);
                    if (ultimo.isPresent() && ultimo.get().getEstado() != EstadoPedido.ABIERTO) {
                        return new PedidoYaPagadoException("El pedido de la mesa " + mesaId + " ya fue pagado o cerrado");
                    }
                    return new RecursoNoEncontradoException("No existe pedido para la mesa " + mesaId);
                });
    }

    /**
     * Obtiene el total bruto (sin impuestos) de un pedido.
     *
     * @param pedidoId identificador del pedido
     * @return total bruto del pedido
     */
    public BigDecimal obtenerTotalPedido(Long pedidoId) {
        return pedidoRepositorio.getTotalPedido(pedidoId);
    }

    /**
     * Calcula el total final aplicando IVA y propina sobre el subtotal.
     */
    public BigDecimal calcularTotalConImpuestos(BigDecimal subtotal) {
        if (subtotal == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = subtotal;
        if (ivaHabilitado) {
            total = total.add(calcularIva(subtotal));
        }
        if (propinaHabilitada) {
            total = total.add(calcularPropina(subtotal));
        }
        return total;
    }

    /**
     * Calcula el monto de IVA sobre un subtotal.
     */
    public BigDecimal calcularIva(BigDecimal subtotal) {
        if (!ivaHabilitado || subtotal == null) {
            return BigDecimal.ZERO;
        }
        return subtotal.multiply(BigDecimal.valueOf(ivaPorcentaje))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /**
     * Calcula el monto de propina sobre un subtotal.
     */
    public BigDecimal calcularPropina(BigDecimal subtotal) {
        if (!propinaHabilitada || subtotal == null) {
            return BigDecimal.ZERO;
        }
        return subtotal.multiply(BigDecimal.valueOf(propinaPorcentaje))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private ResumenPedidoDTO construirResumen(Pedido pedido) {
        List<DetalleElementoDTO> detalles = pedido.getDetalles().stream()
                .map(d -> DetalleElementoDTO.builder()
                        .id(d.getId())
                        .nombrePlatillo(d.getPlatillo().getNombre())
                        .cantidad(d.getCantidad())
                        .precio(d.getPrecio())
                        .subTotal(d.getPrecio().multiply(BigDecimal.valueOf(d.getCantidad())))
                        .notas(d.getNotas())
                        .build())
                .toList();

        BigDecimal subtotal = detalles.stream()
                .map(DetalleElementoDTO::getSubTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal iva = calcularIva(subtotal);
        BigDecimal propina = calcularPropina(subtotal);
        BigDecimal total = subtotal.add(iva).add(propina);

        return ResumenPedidoDTO.builder()
                .pedidoId(pedido.getId())
                .numeroDeMesa(pedido.getMesa().getNumeroDeMesa())
                .detalles(detalles)
                .subtotal(subtotal)
                .iva(iva)
                .propina(propina)
                .total(total)
                .estado(pedido.getEstado())
                .build();
    }
}
