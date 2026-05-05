package com.syntaric.hapi;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfiguration {

    @Bean
    public FilterRegistrationBean<FhirCreateFilter> fhirCreateFilterRegistration(final FhirCreateFilter filter) {
        final FilterRegistrationBean<FhirCreateFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<FhirQueryFilter> fhirQueryFilterRegistration(final FhirQueryFilter filter) {
        final FilterRegistrationBean<FhirQueryFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }
}
