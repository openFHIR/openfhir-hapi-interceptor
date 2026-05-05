package com.syntaric;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FhirContextProducer {

    @Bean
    public FhirContext getFhirContext() {
        return FhirContext.forR4Cached();
    }
}
