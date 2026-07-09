package com.restaurant.servicio;

import com.restaurant.dto.RespuestaMesa;
import com.restaurant.dto.SolicitudCrearMesa;
import com.restaurant.excepcion.RecursoNoEncontradoException;
import com.restaurant.excepcion.TokenInvalidoException;
import com.restaurant.modelo.EstadoMesa;
import com.restaurant.modelo.Mesa;
import com.restaurant.repositorio.MesaRepositorio;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServicioMesa — Tests unitarios")
class ServicioMesaTest {

    @Mock private MesaRepositorio mesaRepositorio;
    @Mock private SimpMessagingTemplate mensajeria;
    @InjectMocks private ServicioMesa servicioMesa;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(servicioMesa, "duracionTokenHoras", 24);
    }

    private Mesa crearMesaTest(Long id, int numero, EstadoMesa estado) {
        Mesa mesa = new Mesa();
        mesa.setId(id);
        mesa.setNumeroDeMesa(numero);
        mesa.setCapacidad(4);
        mesa.setEstado(estado);
        mesa.setTokenSesion("token-valido-123");
        mesa.setTokenExpiraEn(LocalDateTime.now().plusHours(24));
        return mesa;
    }

    @Test
    @DisplayName("Crear mesa exitosamente")
    void crearMesa_exitoso() {
        SolicitudCrearMesa solicitud = SolicitudCrearMesa.builder()
                .numeroDeMesa(5).capacidad(4).build();
        when(mesaRepositorio.existsByNumeroDeMesa(5)).thenReturn(false);
        when(mesaRepositorio.save(any(Mesa.class))).thenAnswer(inv -> {
            Mesa m = inv.getArgument(0);
            m.setId(1L);
            return m;
        });

        RespuestaMesa resp = servicioMesa.crearMesa(solicitud, "http://localhost:8080");

        assertThat(resp.getNumeroDeMesa()).isEqualTo(5);
        assertThat(resp.getEstado()).isEqualTo("DISPONIBLE");
        verify(mesaRepositorio).save(any(Mesa.class));
    }

    @Test
    @DisplayName("Crear mesa con número duplicado lanza excepción")
    void crearMesa_numeroDuplicado() {
        SolicitudCrearMesa solicitud = SolicitudCrearMesa.builder()
                .numeroDeMesa(5).capacidad(4).build();
        when(mesaRepositorio.existsByNumeroDeMesa(5)).thenReturn(true);

        assertThatThrownBy(() -> servicioMesa.crearMesa(solicitud, "http://localhost:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ya existe");
    }

    @Test
    @DisplayName("Buscar mesa por ID exitosamente")
    void buscarPorId_exitoso() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.DISPONIBLE);
        when(mesaRepositorio.findById(1L)).thenReturn(Optional.of(mesa));

        Mesa resultado = servicioMesa.buscarPorId(1L);
        assertThat(resultado.getNumeroDeMesa()).isEqualTo(5);
    }

    @Test
    @DisplayName("Buscar mesa inexistente lanza RecursoNoEncontradoException")
    void buscarPorId_noExiste() {
        when(mesaRepositorio.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicioMesa.buscarPorId(99L))
                .isInstanceOf(RecursoNoEncontradoException.class);
    }

    @Test
    @DisplayName("Validar token correcto no lanza excepción")
    void validarToken_exitoso() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.OCUPADA);
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        assertThatCode(() -> servicioMesa.validarToken(1L, "token-valido-123"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Validar token incorrecto lanza TokenInvalidoException")
    void validarToken_invalido() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.OCUPADA);
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        assertThatThrownBy(() -> servicioMesa.validarToken(1L, "token-malo"))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Validar token expirado lanza TokenInvalidoException")
    void validarToken_expirado() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.OCUPADA);
        mesa.setTokenExpiraEn(LocalDateTime.now().minusHours(1));
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        assertThatThrownBy(() -> servicioMesa.validarToken(1L, "token-valido-123"))
                .isInstanceOf(TokenInvalidoException.class);
    }

    @Test
    @DisplayName("Reservar mesa disponible exitosamente")
    void reservarMesa_exitoso() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.DISPONIBLE);
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        servicioMesa.reservarMesa(1L);

        assertThat(mesa.getEstado()).isEqualTo(EstadoMesa.RESERVADA);
        verify(mesaRepositorio).save(mesa);
        verify(mensajeria).convertAndSend(anyString(), (Object) any());
    }

    @Test
    @DisplayName("Reservar mesa no disponible lanza excepción")
    void reservarMesa_noDisponible() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.OCUPADA);
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        assertThatThrownBy(() -> servicioMesa.reservarMesa(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Liberar mesa reservada exitosamente")
    void liberarMesa_exitoso() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.RESERVADA);
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        servicioMesa.liberarMesa(1L);

        assertThat(mesa.getEstado()).isEqualTo(EstadoMesa.DISPONIBLE);
        verify(mesaRepositorio).save(mesa);
    }

    @Test
    @DisplayName("Liberar mesa no reservada lanza excepción")
    void liberarMesa_noReservada() {
        Mesa mesa = crearMesaTest(1L, 5, EstadoMesa.DISPONIBLE);
        when(mesaRepositorio.findByIdConBloqueo(1L)).thenReturn(Optional.of(mesa));

        assertThatThrownBy(() -> servicioMesa.liberarMesa(1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
