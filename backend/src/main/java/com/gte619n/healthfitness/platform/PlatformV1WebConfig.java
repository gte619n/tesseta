package com.gte619n.healthfitness.platform;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

// Registers a ShallowEtagHeaderFilter scoped to /v1/* (ADR-0020, decision D6):
// every GET gets a content ETag and honours If-None-Match with a 304, cutting
// bandwidth for a polling monitor — with no per-endpoint code. Scoped by URL
// pattern so the first-party /api surface is untouched. Registered as a servlet
// filter (outside the security chain) so it can buffer and hash the final
// response body.
@Configuration
@ConditionalOnProperty(name = "app.platform.enabled", havingValue = "true", matchIfMissing = true)
public class PlatformV1WebConfig {

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> v1EtagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration =
            new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/v1/*");
        registration.setName("v1EtagFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
