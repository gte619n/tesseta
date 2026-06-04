package com.gte619n.healthfitness.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Restricts a controller (or handler method) to admin users. Backed by Spring
 * method security: this is a composed annotation that applies
 * {@code @PreAuthorize("@adminAuthorizer.isAdmin()")}, so it requires
 * {@code @EnableMethodSecurity} (configured in SecurityConfig) and the
 * {@link AdminAuthorizer} bean. The admin allowlist is configured via
 * {@code app.admin.emails}.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@PreAuthorize("@adminAuthorizer.isAdmin()")
public @interface AdminOnly {}
