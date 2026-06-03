package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 5) — locks the per-domain outbox→endpoint map so a replayed
 * mutation lands on the real backend controller path (not the Phase-4 generic
 * `api/me/<table>`). This is the single source of truth for the mapping, so it
 * gets a focused test covering: flat CRUD, the generic fallback (which the
 * existing drain test relies on), the date-composite nutrition entries path, the
 * goal-step composite path, and the singleton profile path.
 */
class OutboxEndpointRegistryTest {

    private val base = "https://api.example.com/".toHttpUrl()

    private fun resolve(table: String, op: OutboxOp, entityId: String) =
        OutboxEndpointRegistry.resolve(base, table, op, entityId)

    @Test
    fun `blood create posts to api me blood`() {
        val r = resolve(MirrorTables.BLOOD_READINGS, OutboxOp.CREATE, "r1")
        assertEquals("POST", r.method)
        assertEquals("https://api.example.com/api/me/blood", r.url.toString())
    }

    @Test
    fun `blood delete targets the reading id`() {
        val r = resolve(MirrorTables.BLOOD_READINGS, OutboxOp.DELETE, "r1")
        assertEquals("DELETE", r.method)
        assertEquals("https://api.example.com/api/me/blood/r1", r.url.toString())
    }

    @Test
    fun `medications fall through to the generic shape used by the drain test`() {
        val r = resolve(MirrorTables.MEDICATIONS, OutboxOp.CREATE, "m1")
        assertEquals("POST", r.method)
        assertEquals("https://api.example.com/api/me/medications", r.url.toString())
    }

    @Test
    fun `unmapped table uses the generic api me table fallback`() {
        val r = resolve(MirrorTables.PROTOCOLS, OutboxOp.UPDATE, "p1")
        assertEquals("PUT", r.method)
        assertEquals("https://api.example.com/api/me/protocols/p1", r.url.toString())
    }

    @Test
    fun `nutrition entry create posts under the date collection`() {
        val r = resolve(MirrorTables.NUTRITION_ENTRIES, OutboxOp.CREATE, "2026-06-02/entry-1")
        assertEquals("POST", r.method)
        assertEquals(
            "https://api.example.com/api/me/nutrition/2026-06-02/entries",
            r.url.toString(),
        )
    }

    @Test
    fun `nutrition entry delete targets the specific entry under its date`() {
        val r = resolve(MirrorTables.NUTRITION_ENTRIES, OutboxOp.DELETE, "2026-06-02/entry-1")
        assertEquals("DELETE", r.method)
        assertEquals(
            "https://api.example.com/api/me/nutrition/2026-06-02/entries/entry-1",
            r.url.toString(),
        )
    }

    @Test
    fun `nutrition entry update is a PATCH (backend is PatchMapping, e g moving meals)`() {
        val r = resolve(MirrorTables.NUTRITION_ENTRIES, OutboxOp.UPDATE, "2026-06-02/entry-1")
        assertEquals("PATCH", r.method)
        assertEquals(
            "https://api.example.com/api/me/nutrition/2026-06-02/entries/entry-1",
            r.url.toString(),
        )
    }

    @Test
    fun `goal step update targets the step nested under its goal and phase`() {
        // #26: structural step edits are a PATCH nested under the phase.
        val r = resolve(MirrorTables.GOAL_STEPS, OutboxOp.UPDATE, "goal-1/phase-2/step-9")
        assertEquals("PATCH", r.method)
        assertEquals(
            "https://api.example.com/api/me/goals/goal-1/phases/phase-2/steps/step-9",
            r.url.toString(),
        )
    }

    @Test
    fun `goal step create posts to the phase's steps collection`() {
        val r = resolve(MirrorTables.GOAL_STEPS, OutboxOp.CREATE, "goal-1/phase-2/step-9")
        assertEquals("POST", r.method)
        assertEquals(
            "https://api.example.com/api/me/goals/goal-1/phases/phase-2/steps",
            r.url.toString(),
        )
    }

    @Test
    fun `goal phase create posts to the goal's phases collection`() {
        val r = resolve(MirrorTables.GOAL_PHASES, OutboxOp.CREATE, "goal-1/phase-2")
        assertEquals("POST", r.method)
        assertEquals("https://api.example.com/api/me/goals/goal-1/phases", r.url.toString())
    }

    @Test
    fun `goal phase update is a patch to the specific phase`() {
        val r = resolve(MirrorTables.GOAL_PHASES, OutboxOp.UPDATE, "goal-1/phase-2")
        assertEquals("PATCH", r.method)
        assertEquals("https://api.example.com/api/me/goals/goal-1/phases/phase-2", r.url.toString())
    }

    @Test
    fun `adherence log posts to the medication's adherence collection`() {
        // #24: composite id is "med/date/window"; CREATE posts to the day's log.
        val r = resolve(MirrorTables.MEDICATION_ADHERENCE, OutboxOp.CREATE, "m1/2026-06-02/MORNING")
        assertEquals("POST", r.method)
        assertEquals("https://api.example.com/api/me/medications/m1/adherence", r.url.toString())
    }

    @Test
    fun `adherence undo deletes the specific date and window`() {
        val r = resolve(MirrorTables.MEDICATION_ADHERENCE, OutboxOp.DELETE, "m1/2026-06-02/EVENING")
        assertEquals("DELETE", r.method)
        assertEquals(
            "https://api.example.com/api/me/medications/m1/adherence/2026-06-02/EVENING",
            r.url.toString(),
        )
    }

    @Test
    fun `adherence idempotency key is derived from med and date`() {
        val key = OutboxEndpointRegistry.idempotencyKey(
            MirrorTables.MEDICATION_ADHERENCE, "m1/2026-06-02/MORNING", "random-uuid",
        )
        assertEquals("adherence:m1:2026-06-02", key)
    }

    @Test
    fun `non-adherence idempotency key is the mutation id`() {
        val key = OutboxEndpointRegistry.idempotencyKey(
            MirrorTables.BLOOD_READINGS, "reading-1", "random-uuid",
        )
        assertEquals("random-uuid", key)
    }

    @Test
    fun `profile is a singleton put with no id segment`() {
        val r = resolve(MirrorTables.USER_PROFILE, OutboxOp.UPDATE, "ignored-id")
        // #21: the live backend path is PATCH /api/me (no PUT /api/me/profile).
        assertEquals("PATCH", r.method)
        assertEquals("https://api.example.com/api/me", r.url.toString())
    }

    @Test
    fun `locations create posts to the gyms controller path`() {
        val r = resolve(MirrorTables.LOCATIONS, OutboxOp.CREATE, "loc-1")
        assertEquals("POST", r.method)
        assertEquals("https://api.example.com/api/me/gyms", r.url.toString())
    }

    @Test
    fun `locations delete targets the gym id`() {
        val r = resolve(MirrorTables.LOCATIONS, OutboxOp.DELETE, "loc-1")
        assertEquals("DELETE", r.method)
        assertEquals("https://api.example.com/api/me/gyms/loc-1", r.url.toString())
    }

    @Test
    fun `nutrition target is a singleton put with no id segment`() {
        val r = resolve(MirrorTables.NUTRITION_TARGETS, OutboxOp.UPDATE, "ignored-id")
        assertEquals("PUT", r.method)
        assertEquals("https://api.example.com/api/me/nutrition/target", r.url.toString())
    }
}
