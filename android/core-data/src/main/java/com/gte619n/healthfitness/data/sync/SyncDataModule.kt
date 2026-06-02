package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.work.WorkManager
import com.gte619n.healthfitness.data.db.dao.OutboxDao
import com.gte619n.healthfitness.data.di.IoDispatcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 4) — Hilt wiring for the sync engine.
 *
 * Provides the [SyncApi] Retrofit binding and the [SyncScheduler]/[WorkManager],
 * and binds the testable seams to their production implementations:
 *  - [MirrorOps] → [MirrorStore]
 *  - [DbWiper] → [RoomDbWiper]
 *  - [OutboxReplayClient] → [RestOutboxReplayClient]
 *
 * [SyncEngine], [OutboxRepository], [DeviceIdProvider], and [SyncFlags] are
 * `@Inject`-constructed; [OutboxRepository]'s `clock` default is supplied here so
 * production uses the wall clock while tests inject a fake.
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncDataModule {

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi =
        retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideSyncScheduler(workManager: WorkManager): SyncScheduler =
        SyncScheduler(workManager)

    @Provides
    @Singleton
    fun provideOutboxRepository(
        outboxDao: OutboxDao,
        mirror: MirrorOps,
        replay: OutboxReplayClient,
        deviceIdProvider: DeviceIdProvider,
        @IoDispatcher io: CoroutineDispatcher,
    ): OutboxRepository =
        OutboxRepository(outboxDao, mirror, replay, deviceIdProvider, io)
}

/** Interface→implementation bindings for the testable sync seams. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncBindsModule {
    @Binds
    @Singleton
    abstract fun bindMirrorOps(impl: MirrorStore): MirrorOps

    @Binds
    @Singleton
    abstract fun bindDbWiper(impl: RoomDbWiper): DbWiper

    @Binds
    @Singleton
    abstract fun bindOutboxReplayClient(impl: RestOutboxReplayClient): OutboxReplayClient

    @Binds
    @Singleton
    abstract fun bindKillSwitchSink(impl: SyncFlags): KillSwitchSink

    @Binds
    @Singleton
    abstract fun bindKillSwitchGate(impl: SyncFlagsKillSwitchGate): KillSwitchGate

    @Binds
    @Singleton
    abstract fun bindDrainTrigger(impl: SchedulerDrainTrigger): DrainTrigger
}
