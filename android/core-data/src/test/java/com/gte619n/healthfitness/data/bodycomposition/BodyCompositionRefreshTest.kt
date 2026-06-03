package com.gte619n.healthfitness.data.bodycomposition

import com.gte619n.healthfitness.data.bodycomposition.api.BodyCompositionApi
import com.gte619n.healthfitness.data.db.dao.BodyCompositionDao
import com.gte619n.healthfitness.data.sync.DrainTrigger
import com.gte619n.healthfitness.data.sync.FakeMirrorOps
import com.gte619n.healthfitness.data.sync.KillSwitchGate
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class BodyCompositionRefreshTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: BodyCompositionRepositoryImpl
    private val mirror = FakeMirrorOps()
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BodyCompositionApi::class.java)
        val support = MirrorRepositorySupport(
            mirror = mirror,
            outbox = mockk(relaxed = true),
            killSwitch = KillSwitchGate { false },
            drainTrigger = DrainTrigger { },
        )
        repo = BodyCompositionRepositoryImpl(
            api = api,
            dao = mockk<BodyCompositionDao>(relaxed = true),
            support = support,
            moshi = moshi,
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `refresh keeps weight and body fat of one weigh-in as distinct mirror rows`() = runBlocking {
        // One weigh-in: a WEIGHT_KG and a BODY_FAT_PERCENT reading that share the
        // same recordId. Keying the mirror on the bare recordId would collapse them
        // into a single row and drop most of the weight series.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    [
                      {"recordId":"1654425077000","metric":"WEIGHT_KG","value":85.0,"sampleTime":"2022-06-05T12:00:00Z"},
                      {"recordId":"1654425077000","metric":"BODY_FAT_PERCENT","value":21.0,"sampleTime":"2022-06-05T12:00:00Z"}
                    ]
                    """.trimIndent(),
                ),
        )

        repo.refresh()

        assertEquals(2, mirror.rows.size)
        assertTrue(mirror.rows.containsKey("bodyComposition:WEIGHT_KG__1654425077000"))
        assertTrue(mirror.rows.containsKey("bodyComposition:BODY_FAT_PERCENT__1654425077000"))
    }
}
