package com.restaurant.dto;

import com.restaurant.modelo.MetodoPago;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SolicitudPagoDTO {
    @Min(value = 1, message = "El ID de la mesa debe ser mayor a 0")
    private Long mesaId;

    @NotNull(message = "El método de pago es obligatorio")
    private MetodoPago metodo;

    @Size(max = 255, message = "El token del proveedor no debe exceder 255 caracteres")
    private String tokenProveedor;
}
