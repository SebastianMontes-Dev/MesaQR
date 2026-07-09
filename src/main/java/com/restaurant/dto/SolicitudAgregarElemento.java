package com.restaurant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SolicitudAgregarElemento {
    @NotNull(message = "El ID del platillo es obligatorio")
    private Long platilloId;

    @NotNull(message = "La cantidad es obligatoria")
    @Min(value = 1, message = "La cantidad debe ser mayor a 0")
    private Integer cantidad = 1;

    @Size(max = 500, message = "Las notas no deben exceder 500 caracteres")
    private String notas;
}
