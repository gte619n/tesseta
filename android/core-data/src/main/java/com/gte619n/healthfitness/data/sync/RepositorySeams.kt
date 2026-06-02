package com.gte619n.healthfitness.data.sync

import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — narrow seams [MirrorRepositorySupport] depends on so it
 * stays unit-testable on the pure JVM.
 *
 * Production binds [KillSwitchGate] to the DataStore-backed [SyncFlags] and
 * [DrainTrigger] to the WorkManager-backed [SyncScheduler]; tests supply trivial
 * in-memory fakes (no DataStore, no WorkManager, no device).
 */

/** Reads the remote kill-switch latch (D13). */
fun interface KillSwitchGate {
    suspend fun isOn(): Boolean
}

/** Requests an outbox drain (production enqueues the WorkManager drain worker). */
fun interface DrainTrigger {
    fun requestDrain()
}

@Singleton
class SyncFlagsKillSwitchGate @Inject constructor(
    private val flags: SyncFlags,
) : KillSwitchGate {
    override suspend fun isOn(): Boolean = flags.isKillSwitchOn()
}

@Singleton
class SchedulerDrainTrigger @Inject constructor(
    private val scheduler: SyncScheduler,
) : DrainTrigger {
    override fun requestDrain() = scheduler.enqueueDrain()
}
