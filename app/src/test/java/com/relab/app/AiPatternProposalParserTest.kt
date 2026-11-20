package com.relab.app

import com.relab.core.Market
import com.relab.core.PatternEvidenceChannel
import com.relab.core.PatternOutcomeMode
import org.junit.Assert.*
import org.junit.Test

class AiPatternProposalParserTest {
    @Test
    fun parsesOnlyExplicitWellFormedProposalBlock() {
        val text = """
            Research notes.
            RELAB_PROPOSALS_JSON
            [
              {"id":"p1","market":"SRIDEVI","session":"main","channel":"OPEN_PANNA","pattern":"123>456","outcomeMode":"EXACT","rationale":"repeated sequence"}
            ]
            END_RELAB_PROPOSALS_JSON
            Citation audit follows.
        """.trimIndent()
        val parsed = AiPatternProposalParser.parse(text)
        assertEquals(1, parsed.proposals.size)
        assertEquals("p1", parsed.proposals.single().id)
        assertEquals(Market.SRIDEVI, parsed.proposals.single().market)
        assertEquals(PatternEvidenceChannel.OPEN_PANNA, parsed.proposals.single().channel)
        assertEquals(PatternOutcomeMode.EXACT, parsed.proposals.single().outcomeMode)
        assertTrue(parsed.issues.isEmpty())
    }

    @Test
    fun rejectsUnknownMarketsAndDuplicateIdsWithoutGuessing() {
        val text = """
            RELAB_PROPOSALS_JSON
            [
              {"id":"same","market":"UNKNOWN","session":"main","channel":"OPEN_PANNA","pattern":"123>456","outcomeMode":"EXACT"},
              {"id":"same","market":"SRIDEVI","session":"main","channel":"OPEN_PANNA","pattern":"123>456","outcomeMode":"EXACT"},
              {"id":"same","market":"SRIDEVI","session":"main","channel":"OPEN_PANNA","pattern":"123>456","outcomeMode":"EXACT"}
            ]
            END_RELAB_PROPOSALS_JSON
        """.trimIndent()
        val parsed = AiPatternProposalParser.parse(text)
        assertTrue(parsed.proposals.isEmpty())
        assertTrue(parsed.issues.any { it.contains("Unknown") || it.contains("UNKNOWN") })
        assertTrue(parsed.issues.any { it.contains("Duplicate") })
    }

    @Test
    fun missingOrMalformedBlockDoesNotProduceProposals() {
        assertTrue(AiPatternProposalParser.parse("just prose").proposals.isEmpty())
        val malformed = AiPatternProposalParser.parse("RELAB_PROPOSALS_JSON\nnot json\nEND_RELAB_PROPOSALS_JSON")
        assertTrue(malformed.proposals.isEmpty())
        assertTrue(malformed.issues.isNotEmpty())
    }
}
