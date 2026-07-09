package com.restaurant.servicio;

import com.restaurant.dto.RespuestaMesa;
import com.restaurant.dto.SolicitudCrearMesa;
import com.restaurant.dto.eventos.EventoCambioEstadoMesa;
import com.restaurant.excepcion.RecursoNoEncontradoException;
import com.restaurant.excepcion.TokenInvalidoException;
import com.restaurant.modelo.EstadoMesa;
import com.restaurant.modelo.Mesa;
import com.restaurant.repositorio.MesaRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Servicio para la gestión de mesas del restaurante.
 * Administra la creación, consulta, reservación, liberación y
 * validación de tokens de sesión de las mesas.
 * Los tokens de sesión se regeneran automáticamente al liberar una mesa.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServicioMesa {

    private final MesaRepositorio mesaRepositorio;
    private final SimpMessagingTemplate mensajeria;

    @Value("${restaurant.token.duracion-horas:24}")
    private int duracionTokenHoras;

    /**
     * Crea una nueva mesa en el sistema con un token de sesión único.
     *
     * @param solicitud datos de la mesa a crear (número y capacidad)
     * @param baseUrl   URL base del servidor para generar la URL del QR
     * @return respuesta con los datos de la mesa creada
     * @throws IllegalArgumentException si el número de mesa ya existe
     */
    @Transactional
    public RespuestaMesa crearMesa(SolicitudCrearMesa solicitud, String baseUrl) {
        if (mesaRepositorio.existsByNumeroDeMesa(solicitud.getNumeroDeMesa())) {
            throw new IllegalArgumentException("El número de mesa ya existe: " + solicitud.getNumeroDeMesa());
        }

        String token = UUID.randomUUID().toString();

        Mesa mesa = new Mesa();
        mesa.setNumeroDeMesa(solicitud.getNumeroDeMesa());
        mesa.setCapacidad(solicitud.getCapacidad());
        mesa.setEstado(EstadoMesa.DISPONIBLE);
        mesa.setTokenSesion(token);
        mesa.setTokenExpiraEn(LocalDateTime.now().plusHours(duracionTokenHoras));

        mesaRepositorio.save(mesa);

        return toRespuesta(mesa, baseUrl);
    }

    /**
     * Obtiene la lista de todas las mesas registradas en el sistema.
     *
     * @return lista de respuestas con los datos de cada mesa
     */
    public List<RespuestaMesa> obtenerTodasLasMesas() {
        return mesaRepositorio.findAll().stream()
                .map(m -> toRespuesta(m, null))
                .toList();
    }

    /**
     * Busca una mesa por su identificador.
     *
     * @param mesaId identificador de la mesa
     * @return la entidad Mesa encontrada
     * @throws RecursoNoEncontradoException si la mesa no existe
     */
    public Mesa buscarPorId(Long mesaId) {
        return mesaRepositorio.findById(mesaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Mesa no encontrada: " + mesaId));
    }

    /**
     * Valida que el token de sesión proporcionado sea válido y no haya expirado
     * para la mesa indicada.
     *
     * @param mesaId identificador de la mesa
     * @param token  token de sesión a validar
     * @throws TokenInvalidoException si el token es inválido o expirado
     */
    @Transactional
    public void validarToken(Long mesaId, String token) {
        Mesa mesa = mesaRepositorio.findByIdConBloqueo(mesaId)
                .orElseThrow(() -> new TokenInvalidoException("Mesa no encontrada"));

        if (mesa.getTokenSesion() == null || !mesa.getTokenSesion().equals(token)) {
            throw new TokenInvalidoException("Token de sesión inválido para la mesa " + mesa.getNumeroDeMesa());
        }

        if (mesa.getTokenExpiraEn() != null && mesa.getTokenExpiraEn().isBefore(LocalDateTime.now())) {
            throw new TokenInvalidoException("El token de sesión expiró para la mesa " + mesa.getNumeroDeMesa());
        }
    }

    /**
     * Actualiza el estado de una mesa. Si el nuevo estado es DISPONIBLE,
     * regenera automáticamente el token de sesión.
     *
     * @param mesaId      identificador de la mesa
     * @param nuevoEstado nuevo estado a asignar
     * @throws RecursoNoEncontradoException si la mesa no existe
     */
    @Transactional
    public void actualizarEstado(Long mesaId, EstadoMesa nuevoEstado) {
        Mesa mesa = mesaRepositorio.findByIdConBloqueo(mesaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Mesa no encontrada: " + mesaId));
        mesa.setEstado(nuevoEstado);

        if (nuevoEstado == EstadoMesa.DISPONIBLE) {
            regenerarToken(mesa);
        }

        mesaRepositorio.save(mesa);
    }

    /**
     * Reserva una mesa que esté en estado DISPONIBLE.
     * Notifica el cambio de estado por WebSocket.
     *
     * @param mesaId identificador de la mesa a reservar
     * @throws RecursoNoEncontradoException si la mesa no existe
     * @throws IllegalArgumentException     si la mesa no está disponible
     */
    @Transactional
    public void reservarMesa(Long mesaId) {
        Mesa mesa = mesaRepositorio.findByIdConBloqueo(mesaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Mesa no encontrada: " + mesaId));

        if (mesa.getEstado() != EstadoMesa.DISPONIBLE) {
            throw new IllegalArgumentException("Solo se pueden reservar mesas disponibles. Estado actual: " + mesa.getEstado());
        }

        mesa.setEstado(EstadoMesa.RESERVADA);
        mesaRepositorio.save(mesa);

        EventoCambioEstadoMesa evento = new EventoCambioEstadoMesa(
                mesaId, mesa.getNumeroDeMesa(), EstadoMesa.RESERVADA
        );
        mensajeria.convertAndSend("/topic/mesas", evento);

        log.info("Mesa {} reservada", mesa.getNumeroDeMesa());
    }

    /**
     * Libera una mesa que esté en estado RESERVADA, devolviéndola a DISPONIBLE.
     * Regenera el token de sesión y notifica por WebSocket.
     *
     * @param mesaId identificador de la mesa a liberar
     * @throws RecursoNoEncontradoException si la mesa no existe
     * @throws IllegalArgumentException     si la mesa no está reservada
     */
    @Transactional
    public void liberarMesa(Long mesaId) {
        Mesa mesa = mesaRepositorio.findByIdConBloqueo(mesaId)
                .orElseThrow(() -> new RecursoNoEncontradoException("Mesa no encontrada: " + mesaId));

        if (mesa.getEstado() != EstadoMesa.RESERVADA) {
            throw new IllegalArgumentException("Solo se pueden liberar mesas reservadas. Estado actual: " + mesa.getEstado());
        }

        mesa.setEstado(EstadoMesa.DISPONIBLE);
        regenerarToken(mesa);
        mesaRepositorio.save(mesa);

        EventoCambioEstadoMesa evento = new EventoCambioEstadoMesa(
                mesaId, mesa.getNumeroDeMesa(), EstadoMesa.DISPONIBLE
        );
        mensajeria.convertAndSend("/topic/mesas", evento);

        log.info("Mesa {} liberada de reserva", mesa.getNumeroDeMesa());
    }

    /**
     * Regenera el token de sesión de una mesa con una nueva fecha de expiración
     * basada en la duración configurada.
     *
     * @param mesa entidad Mesa a la que se le regenerará el token
     */
    @Transactional
    public void regenerarToken(Mesa mesa) {
        mesa.setTokenSesion(UUID.randomUUID().toString());
        mesa.setTokenExpiraEn(LocalDateTime.now().plusHours(duracionTokenHoras));
    }

    /**
     * Verifica si existe un token de sesión válido (no expirado) en el sistema.
     *
     * @param token token de sesión a verificar
     * @return {@code true} si el token existe y no ha expirado
     */
    @Transactional(readOnly = true)
    public boolean existeTokenValido(String token) {
        return mesaRepositorio.findByTokenSesion(token)
                .map(m -> m.getTokenExpiraEn() == null || m.getTokenExpiraEn().isAfter(LocalDateTime.now()))
                .orElse(false);
    }

    private RespuestaMesa toRespuesta(Mesa mesa, String baseUrl) {
        String urlQr = baseUrl != null ? baseUrl + "/api/mesas/" + mesa.getId() + "/qr" : null;
        return RespuestaMesa.builder()
                .id(mesa.getId())
                .numeroDeMesa(mesa.getNumeroDeMesa())
                .capacidad(mesa.getCapacidad())
                .estado(mesa.getEstado().name())
                .urlQr(urlQr)
                .build();
    }
}
