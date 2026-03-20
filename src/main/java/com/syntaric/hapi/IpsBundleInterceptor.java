package com.syntaric.hapi;

import ca.uhn.fhir.interceptor.api.Interceptor;
import com.syntaric.openehr.OpenEhrCdrRegistry;
import org.springframework.stereotype.Component;

/**
 * Marker interceptor registered with HAPI. The actual IPS interception logic lives in
 * {@link IpsBundleFilter}, which handles the request at the servlet layer before HAPI
 * storage — returning 201 + openEHR {@code Location} without storing in HAPI.
 */
@Component
@Interceptor
public class IpsBundleInterceptor {

    public static final String EHRID_SYSTEM = "ehrid";

    // Required by OpenEhrCdrRegistry reference in other classes
    @SuppressWarnings("unused")
    private final OpenEhrCdrRegistry openEhrCdrRegistry;

    public IpsBundleInterceptor(final OpenEhrCdrRegistry openEhrCdrRegistry) {
        this.openEhrCdrRegistry = openEhrCdrRegistry;
    }
}
