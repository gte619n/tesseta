package com.gte619n.healthfitness.integrations.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Shared Google Cloud Storage client.
 *
 * <p>The {@code *Storage} classes previously each called
 * {@code StorageOptions.getDefaultInstance().getService()} in their constructor,
 * building a separate {@link Storage} client per bucket. The client is
 * thread-safe and reusable, so a single injected bean is sufficient and avoids
 * constructing one per component.
 */
@Configuration
public class GcsConfig {

    @Bean
    public Storage storage() {
        return StorageOptions.getDefaultInstance().getService();
    }
}
