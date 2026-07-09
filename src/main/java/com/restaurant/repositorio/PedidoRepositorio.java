package com.restaurant.repositorio;

import com.restaurant.modelo.Pedido;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface PedidoRepositorio extends JpaRepository<Pedido, Long> {

    @EntityGraph("Pedido.detalles")
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Pedido p WHERE p.mesa.id = :mesaId AND p.estado = 'ABIERTO'")
    Optional<Pedido> findActivoByMesaId(@Param("mesaId") Long mesaId);

    @EntityGraph("Pedido.detalles")
    @Query("SELECT p FROM Pedido p WHERE p.mesa.id = :mesaId AND p.estado = 'ABIERTO'")
    Optional<Pedido> findActivoByMesaIdSoloLectura(@Param("mesaId") Long mesaId);

    Optional<Pedido> findFirstByMesaIdOrderByIdDesc(Long mesaId);

    @EntityGraph("Pedido.detalles")
    Optional<Pedido> findById(Long id);

    @Query("SELECT COALESCE(SUM(dp.precio * dp.cantidad), 0) FROM DetallePedido dp WHERE dp.pedido.id = :pedidoId")
    BigDecimal getTotalPedido(@Param("pedidoId") Long pedidoId);
}
