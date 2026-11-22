package com.relab.app

import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AiResearchMemoryStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("relab_ai_research_memory.db")
    }

    @After fun tearDown() {
        context.deleteDatabase("relab_ai_research_memory.db")
    }

    @Test fun autoLoopRoundCheckpointsSurviveStoreRecreationAndInterruption() {
        val firstStore = AiResearchMemoryStore(context)
        val session = firstStore.createLoopSession("Question", "immutable evidence", "old UNVERIFIED note", 3)
        firstStore.saveLoopStage(session.id, 1, "GEMINI_ANALYSIS", "research round one")
        firstStore.saveLoopStage(session.id, 1, "GROQ_REVIEW", "audit round one")
        firstStore.saveLoopStage(session.id, 1, "FINAL_SYNTHESIS", "synthesis round one")
        firstStore.saveLoopStage(session.id, 2, "GEMINI_ANALYSIS", "research round two")
        firstStore.markLoopInterrupted(session.id, "network disconnected")
        firstStore.close()

        val reopenedStore = AiResearchMemoryStore(context)
        val resumed = reopenedStore.latestResumableLoop()

        assertNotNull(resumed)
        assertEquals(session.id, resumed!!.id)
        assertEquals("INTERRUPTED", resumed.state)
        assertEquals("network disconnected", resumed.lastError)
        assertEquals(3, resumed.maxRounds)
        assertEquals("immutable evidence", resumed.evidence)
        assertEquals("research round one", resumed.rounds[0].checkpoint.geminiAnalysis)
        assertEquals("audit round one", resumed.rounds[0].checkpoint.groqReview)
        assertEquals("synthesis round one", resumed.rounds[0].checkpoint.finalSynthesis)
        assertEquals("research round two", resumed.rounds[1].checkpoint.geminiAnalysis)
        assertEquals("", resumed.rounds[1].checkpoint.groqReview)
        assertEquals("", resumed.rounds[2].checkpoint.finalSynthesis)
        reopenedStore.close()
    }


    @Test fun refusesToCompleteLoopWhenAnyRoundOrCheckpointStageIsMissing() {
        val store = AiResearchMemoryStore(context)
        val session = store.createLoopSession("Question", "evidence", "", 2)
        store.saveLoopStage(session.id, 1, "GEMINI_ANALYSIS", "Gemini round one")
        store.saveLoopStage(session.id, 1, "GROQ_REVIEW", "Groq round one")
        store.saveLoopStage(session.id, 1, "FINAL_SYNTHESIS", "Synthesis round one")

        val incomplete = AiResearchLoopResult(
            listOf(AiResearchLoopRoundResult(1, AiResearchResult("Gemini", "Groq", "Synthesis")))
        )
        try {
            store.markLoopCompleted(session.id, incomplete)
            fail("A two-round loop must not complete with only one round")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("every configured round"))
        }

        val resumable = store.latestResumableLoop()
        assertNotNull(resumable)
        assertEquals("PENDING", resumable!!.state)
        assertEquals(2, resumable.maxRounds)
        store.close()
    }

    @Test fun completedLoopIsSavedAsUnverifiedHistoryAndIsNotOfferedForResume() {
        val store = AiResearchMemoryStore(context)
        val session = store.createLoopSession("Question", "evidence", "", 1)
        val result = AiResearchLoopResult(
            listOf(AiResearchLoopRoundResult(1, AiResearchResult("Gemini", "Groq", "Final synthesis")))
        )
        store.saveLoopStage(session.id, 1, "GEMINI_ANALYSIS", "Gemini")
        store.saveLoopStage(session.id, 1, "GROQ_REVIEW", "Groq")
        store.saveLoopStage(session.id, 1, "FINAL_SYNTHESIS", "Final synthesis")
        store.markLoopCompleted(session.id, result)

        assertNull(store.latestResumableLoop())
        val history = store.latest()
        assertEquals(1, history.size)
        assertTrue(history.single().finalSynthesis.contains("Verification state: UNVERIFIED"))
        assertTrue(history.single().finalSynthesis.contains("No chart records were modified."))
        store.close()
    }
}
