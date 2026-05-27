package com.gte619n.healthfitness.mobile.di

import javax.inject.Qualifier
import kotlin.annotation.AnnotationRetention.BINARY

/**
 * Marks a `CoroutineScope` whose lifetime matches the Application — used
 * for fire-and-forget work that must outlive any individual screen (e.g.
 * the wear-token publish on auth bootstrap).
 */
@Qualifier
@Retention(BINARY)
annotation class ApplicationScope
