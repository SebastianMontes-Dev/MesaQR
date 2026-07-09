package com.restaurant.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SolicitudCrearMesa {
    @NotNull(message = "El número de mesa es obligatorio")
    @Min(value = 1, message = "El número de mesa debe ser mayor a 0")
    private Integer numeroDeMesa;

    @NotNull(message = "La capacidad es obligatoria")
    @Min(value = 1, message = "La capacidad debe ser mayor a 0")
    private Integer capacidad;
}
