package com.restaurant.integracion;

import com.restaurant.modelo.Mesa;
import com.restaurant.modelo.Platillo;
import com.restaurant.repositorio.MesaRepositorio;
import com.restaurant.repositorio.PlatilloRepositorio;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Test de integración que valida el flujo completo:
 * crear mesa → crear pedido → agregar ítems → pagar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Flujo completo de pedido — Test de integración")
class FlujoCompletoPedidoTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MesaRepositorio mesaRepositorio;
    @Autowired private PlatilloRepositorio platilloRepositorio;

    private static Long mesaId;
    private static String token;
    private static Long platilloId;

    @BeforeAll
    static void limpiarEstadoAnterior(@Autowired MesaRepositorio mesaRepo,
                                       @Autowired PlatilloRepositorio platilloRepo) {
        // Crear un platillo de prueba
        Platillo platillo = new Platillo();
        platillo.setNombre("Hamburguesa Test");
        platillo.setDescripcion("Hamburguesa de prueba");
        platillo.setPrecio(BigDecimal.valueOf(14000));
        platillo.setCategoria("Hamburguesas");
        platillo.setDisponible(true);
        platillo = platilloRepo.save(platillo);
        platilloId = platillo.getId();
    }

    @Test
    @Order(1)
    @DisplayName("1. Crear mesa y obtener token")
    void paso1_crearMesa() throws Exception {
        MvcResult resultado = mockMvc.perform(post("/api/mesas")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"numeroDeMesa\": 99, \"capacidad\": 4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.numeroDeMesa").value(99))
                .andExpect(jsonPath("$.estado").value("DISPONIBLE"))
                .andReturn();

        // Extraer mesaId del JSON de respuesta
        String body = resultado.getResponse().getContentAsString();
        mesaId = Long.valueOf(body.replaceAll(".*\"id\":(\\d+).*", "$1"));

        // Obtener token de la BD
        Mesa mesa = mesaRepositorio.findById(mesaId).orElseThrow();
        token = mesa.getTokenSesion();

        Assertions.assertNotNull(token);
    }

    @Test
    @Order(2)
    @DisplayName("2. Crear pedido para la mesa")
    void paso2_crearPedido() throws Exception {
        mockMvc.perform(post("/api/pedidos/mesa/" + mesaId)
                        .header("X-Session-Token", token))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(3)
    @DisplayName("3. Agregar ítem al pedido")
    void paso3_agregarItem() throws Exception {
        mockMvc.perform(post("/api/pedidos/mesa/" + mesaId + "/items")
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"platilloId\": " + platilloId + ", \"cantidad\": 2, \"notas\": \"sin cebolla\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(4)
    @DisplayName("4. Verificar resumen del pedido con IVA")
    void paso4_verificarResumen() throws Exception {
        mockMvc.perform(get("/api/pedidos/mesa/" + mesaId)
                        .header("X-Session-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numeroDeMesa").value(99))
                .andExpect(jsonPath("$.estado").value("ABIERTO"))
                .andExpect(jsonPath("$.detalles").isArray())
                .andExpect(jsonPath("$.detalles[0].nombrePlatillo").value("Hamburguesa Test"))
                .andExpect(jsonPath("$.detalles[0].cantidad").value(2))
                .andExpect(jsonPath("$.subtotal").value(28000))
                .andExpect(jsonPath("$.total").isNumber());
    }

    @Test
    @Order(5)
    @DisplayName("5. Pagar con tarjeta exitosamente")
    void paso5_pagarConTarjeta() throws Exception {
        mockMvc.perform(post("/api/pagos/mesa/" + mesaId)
                        .header("X-Session-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"metodo\": \"TARJETA\", \"tokenProveedor\": \"tok_test_4242\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("COMPLETADO"))
                .andExpect(jsonPath("$.monto").isNumber());
    }

    @Test
    @Order(6)
    @DisplayName("6. Verificar que la mesa quedó DISPONIBLE")
    void paso6_verificarMesaDisponible() throws Exception {
        mockMvc.perform(get("/api/mesas/" + mesaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DISPONIBLE"));
    }
}
