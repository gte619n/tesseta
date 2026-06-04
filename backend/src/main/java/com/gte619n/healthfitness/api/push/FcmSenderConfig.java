package com.gte619n.healthfitness.api.push;

import com.gte619n.healthfitness.core.push.FcmSendResult;
import com.gte619n.healthfitness.core.push.FcmSender;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default {@link FcmSender} wiring for the FCM fan-out (IMPL-AND-20, Phase 2).
 *
 * <p>Provides a <b>no-op</b> transport whenever FCM is <b>not</b> enabled
 * ({@code app.fcm.enabled != true}), so:
 * <ul>
 *   <li>in deployed runs ({@code app.fcm.enabled=true}) this bean is absent and
 *       the Firebase-backed transport in {@code integrations} is the only
 *       {@link FcmSender};</li>
 *   <li>in tests / local-without-ADC ({@code enabled=false}) this no-op is the
 *       default {@link FcmSender}, so the fan-out pipeline runs end-to-end
 *       (tokens loaded, origin suppressed) without any network message or
 *       credentials. Tests that need to assert recipients install a capturing
 *       {@code RecordingFcmSender} marked {@code @Primary}, which then wins the
 *       injection over this no-op.</li>
 * </ul>
 *
 * <p>Kept in {@code api} (which already depends on Spring Boot autoconfigure for
 * {@code @ConditionalOnMissingBean}, cf. {@code SyncWriteConfig}) rather than in
 * {@code core}, which is a plain library module without autoconfigure.
 */
@Configuration
public class FcmSenderConfig {

    private static final Logger log = System.getLogger(FcmSenderConfig.class.getName());

    @Bean
    @ConditionalOnProperty(name = "app.fcm.enabled", havingValue = "false", matchIfMissing = true)
    FcmSender noOpFcmSender() {
        return (tokens, collections) -> {
            log.log(Level.DEBUG, () -> "FCM disabled — skipping fan-out to "
                + tokens.size() + " token(s) for collections=" + collections);
            return FcmSendResult.empty();
        };
    }
}
