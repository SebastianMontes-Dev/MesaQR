package com.restaurant.configuracion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Configuración web general de la aplicación.
 * Registra filtros HTTP necesarios para operación detrás de proxies reversos.
 */
@Configuration
public class ConfiguracionWeb {

    /**
     * Procesa los headers X-Forwarded-* para que las URLs generadas
     * (como las de códigos QR) funcionen correctamente detrás de
     * proxies reversos como Nginx o Traefik.
     */
    @Bean
    ForwardedHeaderFilter forwardedHeaderFilter() {
        return new ForwardedHeaderFilter();
    }
}
