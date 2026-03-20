package com.syntaric.openfhir;

import ca.uhn.fhir.rest.server.RestfulServer;
import com.syntaric.ips.IpsDirectProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

@Configuration
@Slf4j
public class OpenFhirServerConfiguration {

    private final IpsDirectProvider ipsDirectProvider;
    private final ApplicationContext applicationContext;

    public OpenFhirServerConfiguration(final IpsDirectProvider ipsDirectProvider,
                                       final ApplicationContext applicationContext) {
        this.ipsDirectProvider = ipsDirectProvider;
        this.applicationContext = applicationContext;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void registerProviders() {
        try {
            final RestfulServer restfulServer = applicationContext.getBean(RestfulServer.class);
            restfulServer.registerProvider(ipsDirectProvider);
            log.info("Registered IpsDirectProvider with RestfulServer");
        } catch (final Exception e) {
            log.error("Failed to register plain providers with RestfulServer", e);
        }
    }
}

