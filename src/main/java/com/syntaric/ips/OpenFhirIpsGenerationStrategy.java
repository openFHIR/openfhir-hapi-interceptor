package com.syntaric.ips;

import ca.uhn.fhir.jpa.ips.api.ISectionResourceSupplier;
import ca.uhn.fhir.jpa.ips.api.Section;
import ca.uhn.fhir.jpa.ips.jpa.DefaultJpaIpsGenerationStrategy;
import jakarta.annotation.Nonnull;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class OpenFhirIpsGenerationStrategy extends DefaultJpaIpsGenerationStrategy {

    private final IPSProcessor ipsProcessor;

    public OpenFhirIpsGenerationStrategy(final IPSProcessor ipsProcessor) {
        this.ipsProcessor = ipsProcessor;
    }

    @Nonnull
    @Override
    public ISectionResourceSupplier getSectionResourceSupplier(@Nonnull final Section theSection) {
        return ipsProcessor;
    }
}
