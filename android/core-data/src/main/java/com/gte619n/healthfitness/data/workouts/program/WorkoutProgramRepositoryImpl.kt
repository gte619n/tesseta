package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgramRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only repository over [WorkoutProgramApi]. Each call runs on
 * [Dispatchers.IO] and wraps the result in a [Result] (errors surface as
 * `Result.failure` for the ViewModels to map to UI error state), mirroring the
 * IMPL-AND-06 LocationRepositoryImpl style.
 */
@Singleton
class WorkoutProgramRepositoryImpl @Inject constructor(
    private val api: WorkoutProgramApi,
) : WorkoutProgramRepository {

    override suspend fun list(): Result<List<WorkoutProgram>> =
        withContext(Dispatchers.IO) {
            runCatching { api.list().map { it.toDomain() } }
        }

    override suspend fun get(programId: String): Result<WorkoutProgram> =
        withContext(Dispatchers.IO) {
            runCatching { api.get(programId).toDomain() }
        }

    override suspend fun calendar(
        programId: String,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<ScheduledWorkout>> =
        withContext(Dispatchers.IO) {
            runCatching {
                api.calendar(programId, from.toString(), to.toString()).map { it.toDomain() }
            }
        }
}
