package com.relab.app

import com.relab.core.ChartRecord
import com.relab.core.Market
import com.relab.core.PatternEvidenceChannel
import com.relab.core.PatternOutcomeMode
import com.relab.core.ResearchPatternProposal
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AiResearchProposalGrounderTest {
    private fun row(day: Int, open: String, session: String = "main"): ChartRecord {
        val close = "444"
        val jodi = "${open.sumOf { it.digitToInt() } % 10}${close.sumOf { it.digitToInt() } % 10}"
        return ChartRecord(
            market = Market.SRIDEVI,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open, jodi = jodi, closePanna = close,
            sourceId = "source-$day", sessionId = session
        )
    }
    private fun proposal(pattern: String = "111>222", session: String = "main", mode: PatternOutcomeMode = PatternOutcomeMode.EXACT) =
        ResearchPatternProposal(
            id = "p1", market = Market.SRIDEVI, sessionId = session,
            channel = PatternEvidenceChannel.OPEN_PANNA, pattern = pattern, outcomeMode = mode
        )

    @Test fun acceptsOnlyASequencePresentInTheExactMarketSessionAndChannel() {
        val result = AiResearchProposalGrounder.ground(listOf(row(0, "111"), row(1, "222"), row(2, "333")), listOf(proposal()), LocalDate.of(2026, 2, 1))
        assertEquals(listOf("p1"), result.accepted.map { it.id })
        assertTrue(result.rejected.isEmpty())
    }

    @Test fun rejectsInventedPatternsAndMissingSessions() {
        val result = AiResearchProposalGrounder.ground(
            listOf(row(0, "111"), row(1, "222")),
            listOf(proposal("999>888"), proposal(session = "evening").copy(id = "missing")),
            LocalDate.of(2026, 2, 1)
        )
        assertTrue(result.accepted.isEmpty())
        assertEquals(2, result.rejected.size)
        assertTrue(result.rejected.any { it.reason.contains("does not occur") })
        assertTrue(result.rejected.any { it.reason.contains("No pre-cutoff chart rows") })
    }

    @Test fun excludesTargetDateAndFutureRowsFromDiscoveryGrounding() {
        val result = AiResearchProposalGrounder.ground(listOf(row(0, "111"), row(1, "222")), listOf(proposal()), LocalDate.of(2026, 1, 2))
        assertTrue(result.accepted.isEmpty())
        assertTrue(result.rejected.single().reason.contains("does not occur"))
    }

    @Test fun familyMovementCanMatchObservedFamilyVariantsButExactCannot() {
        val rows = listOf(row(0, "111"), row(1, "222"), row(2, "666"), row(3, "777"))
        val exact = AiResearchProposalGrounder.ground(rows, listOf(proposal("111>222").copy(id = "exact")), LocalDate.of(2026, 2, 1))
        val family = AiResearchProposalGrounder.ground(rows, listOf(proposal("111>222", mode = PatternOutcomeMode.FAMILY_MOVEMENT).copy(id = "family")), LocalDate.of(2026, 2, 1))
        assertEquals("exact", exact.accepted.single().id)
        assertEquals("family", family.accepted.single().id)
    }

    @Test fun malformedRowsCannotBeUsedToCompleteASequence() {
        val invalid = row(1, "222").copy(jodi = "99")
        val result = AiResearchProposalGrounder.ground(listOf(row(0, "111"), invalid, row(2, "222")), listOf(proposal()), LocalDate.of(2026, 2, 1))
        assertTrue(result.accepted.isEmpty())
    }

    @Test fun duplicateDatesRejectProposalAndDuplicateIdsRejectAllCopies() {
        val duplicateDate = AiResearchProposalGrounder.ground(listOf(row(0, "111"), row(1, "222"), row(1, "333")), listOf(proposal()), LocalDate.of(2026, 2, 1))
        assertTrue(duplicateDate.accepted.isEmpty())
        assertTrue(duplicateDate.rejected.single().reason.contains("Duplicate dates"))
        val duplicateIds = AiResearchProposalGrounder.ground(listOf(row(0, "111"), row(1, "222")), listOf(proposal(), proposal()), LocalDate.of(2026, 2, 1))
        assertTrue(duplicateIds.accepted.isEmpty())
        assertEquals(2, duplicateIds.rejected.size)
    }

    @Test fun jodiProposalsRequireTwoDigitObservedValues() {
        val valid = ResearchPatternProposal("jodi", Market.SRIDEVI, "main", PatternEvidenceChannel.JODI, "32>62")
        val result = AiResearchProposalGrounder.ground(listOf(row(0, "111"), row(1, "222")), listOf(valid), LocalDate.of(2026, 2, 1))
        assertEquals("jodi", result.accepted.single().id)
    }
}
