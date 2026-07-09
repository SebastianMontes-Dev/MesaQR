package com.restaurant.dto;

import com.restaurant.modelo.EstadoPedido;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class ResumenPedidoDTO {
    private Long pedidoId;
    private Integer numeroDeMesa;
    private List<DetalleElementoDTO> detalles;
    private BigDecimal subtotal;
    private BigDecimal iva;
    private BigDecimal propina;
    private BigDecimal total;
    private EstadoPedido estado;
}
