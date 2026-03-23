package com.syntaric.openfhir;

import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.interceptor.CorsInterceptor;
import com.syntaric.ips.IpsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@Slf4j
public class OpenFhirServerConfiguration {

    private final IpsProvider ipsProvider;
    private final ApplicationContext applicationContext;

    public OpenFhirServerConfiguration(final IpsProvider ipsProvider,
                                       final ApplicationContext applicationContext) {
        this.ipsProvider = ipsProvider;
        this.applicationContext = applicationContext;
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        final CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("*");
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.addAllowedHeader("*");
        config.addExposedHeader(Constants.HEADER_LOCATION);
        config.addExposedHeader(Constants.HEADER_CONTENT_LOCATION);
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        final FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setOrder(0);
        return registration;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void registerProviders() {
        try {
            final RestfulServer restfulServer = applicationContext.getBean(RestfulServer.class);
            restfulServer.registerProvider(ipsProvider);
            log.info("Registered IpsDirectProvider with RestfulServer");

            // cors
            final CorsConfiguration config = new CorsConfiguration();
            config.addAllowedHeader(Constants.HEADER_X_FHIR_STARTER);
            config.addAllowedHeader(Constants.HEADER_CORS_ORIGIN);
            config.addAllowedHeader(Constants.HEADER_ACCEPT);
            config.addAllowedHeader(Constants.HEADER_X_REQUESTED_WITH);
            config.addAllowedHeader(Constants.HEADER_CONTENT_TYPE);

            config.addAllowedOrigin("*");

            config.addExposedHeader(Constants.HEADER_LOCATION);
            config.addExposedHeader(Constants.HEADER_CONTENT_LOCATION);
            config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
            restfulServer.registerInterceptor(new CorsInterceptor(config));
        } catch (final Exception e) {
            log.error("Failed to register plain providers with RestfulServer", e);
        }
    }
}

