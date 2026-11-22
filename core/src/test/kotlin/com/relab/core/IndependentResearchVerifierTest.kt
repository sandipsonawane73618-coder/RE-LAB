package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class IndependentResearchVerifierTest {
    private val start = LocalDate.of(2026, 1, 1)

    private fun row(day: Int, open: String, market: Market = Market.SRIDEVI, session: String = "main") =
        ChartRecord(
            market = market,
            date = start.plusDays(day.toLong()),
            openPanna = open,
            jodi = "12",
            closePanna = "444",
            sourceId = "consensus-$day",
            sessionId = session
        )

    private fun proposal(market: Market = Market.SRIDEVI, session: String = "main") =
        ResearchPatternProposal(
            "independent-proposal", market, session,
            PatternEvidenceChannel.OPEN_PANNA, "111>222"
        )

    @Test fun evaluatesOnlyTheChronologicalHoldoutAndKeepsScopeIsolated() {
        val data = (0 until 100).map { day ->
            row(day, when (day % 3) { 0 -> "111"; 1 -> "222"; else -> "333" })
        } + (0 until 100).map { day ->
            row(day, "999", Market.KALYAN, "night")
        }
        val report = IndependentResearchVerifier.verify(
            data, listOf(proposal()), start.plusDays(100), minimumEvaluations = 1
        )
        val result = report.results.single()
        assertEquals(70, result.trainingRows)
        assertEquals(30, result.holdoutRows)
        assertEquals(start.plusDays(70), result.holdoutStart)
        assertEquals(1, report.results.size)
        assertTrue(result.evaluatedOccurrences > 0)
        assertNotEquals(ResearchVerificationStatus.VERIFIED, result.status)
    }

    @Test fun rowsAtOrAfterCutoffCannotChangeHoldoutResult() {
        val data = (0 until 100).map { day ->
            row(day, when (day % 3) { 0 -> "111"; 1 -> "222"; else -> "333" })
        }
        val cutoff = start.plusDays(100)
        val before = IndependentResearchVerifier.verify(data, listOf(proposal()), cutoff, 1)
        val after = IndependentResearchVerifier.verify(
            data + listOf(row(100, "111"), row(101, "222"), row(102, "333")),
            listOf(proposal()), cutoff, 1
        )
        assertEquals(before, after)
    }

    @Test fun duplicateDatesAreRejectedAndInvalidRowsAreNotBridged() {
        val data = (0 until 100).map { day ->
            row(day, when (day % 3) { 0 -> "111"; 1 -> "222"; else -> "333" })
        }
        val duplicate = data + data[10]
        val duplicateResult = IndependentResearchVerifier.verify(
            duplicate, listOf(proposal()), start.plusDays(100), 1
        ).results.single()
        assertEquals(ResearchVerificationStatus.REJECTED, duplicateResult.status)
        assertTrue(duplicateResult.reason.orEmpty().contains("Duplicate"))

        val invalid = data.toMutableList()
        invalid[15] = invalid[15].copy(openPanna = "bad")
        val invalidResult = IndependentResearchVerifier.verify(
            invalid, listOf(proposal()), start.plusDays(100), 1
        ).results.single()
        assertEquals(ResearchVerificationStatus.REJECTED, invalidResult.status)
        assertTrue(invalidResult.reason.orEmpty().contains("Invalid row"))
    }

    @Test fun insufficientHoldoutEvidenceRemainsUncertain() {
        val data = (0 until 20).map { day ->
            row(day, if (day % 2 == 0) "111" else "222")
        }
        val result = IndependentResearchVerifier.verify(
            data, listOf(proposal()), start.plusDays(20), minimumEvaluations = 10
        ).results.single()
        assertEquals(ResearchVerificationStatus.UNCERTAIN, result.status)
        assertTrue(result.reason.orEmpty().isNotBlank())
    }

    @Test fun duplicateProposalIdsAreRejected() {
        val data = (0 until 100).map { day ->
            row(day, when (day % 3) { 0 -> "111"; 1 -> "222"; else -> "333" })
        }
        val report = IndependentResearchVerifier.verify(
            data, listOf(proposal(), proposal()), start.plusDays(100), 1
        )
        assertTrue(report.results.all { it.status == ResearchVerificationStatus.REJECTED })
        assertTrue(report.results.all { it.reason.orEmpty().contains("Duplicate proposal id") })
    }
}
