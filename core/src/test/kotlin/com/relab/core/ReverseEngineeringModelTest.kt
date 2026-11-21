package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReverseEngineeringModelTest {
    private fun row(
        market: Market,
        day: Int,
        open: String = "166",
        close: String = "277",
        session: String = "main"
    ): ChartRecord {
        val openAnk = open.sumOf { it.digitToInt() } % 10
        val closeAnk = close.sumOf { it.digitToInt() } % 10
        return ChartRecord(
            market = market,
            date = LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            openPanna = open,
            jodi = "$openAnk$closeAnk",
            closePanna = close,
            sourceId = "fixture",
            sessionId = session
        )
    }

    private fun history(market: Market = Market.SRIDEVI): List<ChartRecord> =
        (0 until 40).map { row(market, it) }

    @Test fun supportsAllNineMarketsAndAbstainsForInsufficientHistory() {
        val result = ReverseEngineeringModel.rank(
            history(), Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN, minimumHistory = 50
        )
        assertTrue(result.abstained)
        assertTrue(result.candidates.isEmpty())
        val milan = ReverseEngineeringModel.rank(
            listOf(row(Market.MILAN_DAY, 1)), Market.MILAN_DAY, "main",
            LocalDate.of(2026, 2, 1), ReverseEngineeringSide.OPEN
        )
        assertTrue("Milan Day must be supported and abstain on insufficient history", milan.abstained)
        assertEquals(1, milan.trainingRecords)
    }

    @Test fun exactAndFamilyEvidenceRankRepeatedPannaFirst() {
        val result = ReverseEngineeringModel.rank(
            history(), Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN, minimumHistory = 30
        )
        assertFalse(result.abstained)
        assertEquals(40, result.trainingRecords)
        assertEquals("166", result.candidates.first().panna)
        assertEquals("111", result.candidates.first().familySequence)
        assertEquals(3, result.candidates.first().ank)
        assertEquals(PannaType.DP, result.candidates.first().pannaType)
        assertTrue(result.activeEvidenceLayers.contains("Ank distribution"))
        assertTrue(result.activeEvidenceLayers.contains("Digit-position distribution"))
        assertTrue(result.activeEvidenceLayers.contains("SP/DP/TP panna type"))
        assertTrue(result.activeEvidenceLayers.contains("Reverse-panna recurrence"))
        assertTrue(result.activeEvidenceLayers.contains("Target weekday"))
        assertTrue(result.activeEvidenceLayers.contains("Previous-state transition"))
        assertTrue(result.note.contains("not a calibrated probability"))
    }

    @Test fun familyEquivalencePreservesOriginalPannaDigits() {
        val result = ReverseEngineeringModel.rank(
            history(), Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN, minimumHistory = 30, limit = 100
        )
        val sameFamily = result.candidates.filter { it.familySequence == "111" }
        assertTrue(sameFamily.any { it.panna == "166" })
        assertTrue(sameFamily.any { it.panna == "611" })
        assertTrue(sameFamily.any { it.panna == "116" })
        assertTrue(sameFamily.all { it.familyEvidence == sameFamily.first().familyEvidence })
    }

    @Test fun familyModelRanksOnlyBaseFiveSequences() {
        val result = ReverseEngineeringModel.rank(
            history(), Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN, minimumHistory = 30
        )
        assertTrue(result.familyCandidates.isNotEmpty())
        assertTrue(result.familyCandidates.all { candidate ->
            candidate.familySequence.length == 3 && candidate.familySequence.all { it in '0'..'4' }
        })
        assertEquals("111", result.familyCandidates.first().familySequence)
    }

    @Test fun futureRowsCannotChangeHistoricalRanking() {
        val past = history()
        val target = LocalDate.of(2026, 3, 1)
        val before = ReverseEngineeringModel.rank(
            past, Market.SRIDEVI, "main", target, ReverseEngineeringSide.OPEN, minimumHistory = 30
        )
        val futureAdded = past + row(Market.SRIDEVI, 100, open = "999", close = "000")
        val after = ReverseEngineeringModel.rank(
            futureAdded, Market.SRIDEVI, "main", target, ReverseEngineeringSide.OPEN, minimumHistory = 30
        )
        assertEquals(before.candidates.map { it.panna to it.score }, after.candidates.map { it.panna to it.score })
    }

    @Test fun closeModelUsesKnownOpenConditionalEvidence() {
        val rows = history()
        val result = ReverseEngineeringModel.rank(
            rows, Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.CLOSE, knownOpenPanna = "166", minimumHistory = 30
        )
        assertTrue(result.activeEvidenceLayers.contains("Known open-to-close conditional"))
        assertEquals("277", result.candidates.first().panna)
    }

    @Test fun crossMarketLayerUsesOnlyPriorSameDateRows() {
        val sridevi = history(Market.SRIDEVI)
        val kalyan = (0 until 40).map { row(Market.KALYAN, it, open = "277", close = "368") }
        val result = ReverseEngineeringModel.rank(
            sridevi + kalyan, Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN, minimumHistory = 30
        )
        assertTrue(result.activeEvidenceLayers.contains("Lagged cross-market conditional"))
        assertEquals("166", result.candidates.first().panna)
    }

    @Test fun backtestReportsTopKAndBaselineWithoutFutureLeakage() {
        val result = ReverseEngineeringModel.backtest(
            history(), Market.SRIDEVI, "main", ReverseEngineeringSide.OPEN,
            targetLimit = 5, minimumHistory = 30
        )
        assertEquals(5, result.requestedTargets)
        assertEquals(5, result.evaluatedTargets)
        assertEquals(0, result.abstainedTargets)
        assertEquals(5, result.top1ExactHits)
        assertEquals(5, result.top10ExactHits)
        assertEquals(5, result.top1AnkHits)
        assertEquals(5, result.top10AnkHits)
        assertEquals(5, result.top1FamilyHits)
        assertEquals(5, result.priorModeFamilyHits)
        assertEquals(5, result.globalExactTop1Hits)
        assertEquals(5, result.globalExactTop10Hits)
        assertEquals(5, result.weekdayExactTop1Hits)
        assertEquals(5, result.weekdayExactTop10Hits)
        assertEquals(5, result.previousTransitionFamilyHits)
    }

    @Test fun frequencyBaselinesExcludeTargetAndFutureRows() {
        val rows = listOf(
            row(Market.SRIDEVI, 0, open = "111"),
            row(Market.SRIDEVI, 1, open = "222"),
            row(Market.SRIDEVI, 2, open = "111"),
            row(Market.SRIDEVI, 3, open = "222"),
            row(Market.SRIDEVI, 4, open = "333"),
            row(Market.SRIDEVI, 5, open = "111")
        )
        val result = ReverseEngineeringModel.backtest(
            rows, Market.SRIDEVI, "main", ReverseEngineeringSide.OPEN,
            targetLimit = 1, minimumHistory = 5, candidateLimit = 2
        )
        // The final target is 111. Earlier global frequency is tied 111/222;
        // deterministic lexical tie-break chooses 111. The target itself must not
        // inflate its own baseline count.
        assertEquals(1, result.globalExactTop1Hits)
        assertEquals(1, result.globalExactTop10Hits)
    }

    @Test fun duplicateDatesAreRejectedBeforeTraining() {
        val rows = history()
        try {
            ReverseEngineeringModel.rank(
                rows + rows.first(), Market.SRIDEVI, "main", LocalDate.of(2026, 3, 1),
                ReverseEngineeringSide.OPEN, minimumHistory = 30
            )
            fail("Duplicate dates must not be silently accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("Duplicate dates"))
        }
    }

    @Test fun directionalPatternScanUsesOnlyRowsBeforeTargetDate() {
        val past = history()
        val future = row(Market.SRIDEVI, 100, open = "123", close = "456").copy(sourceId = "future-only")
        val report = ReverseEngineeringModel.scanDirectionalPatterns(
            past + future,
            Market.SRIDEVI,
            "main",
            LocalDate.of(2026, 3, 1),
            ReverseEngineeringSide.OPEN,
            maximumStep = 1
        ).single()

        assertEquals(40, report.acceptedRecords)
        assertEquals(0, report.rejectedRecords)
        assertFalse(report.occurrences.any { it.sourceIds.contains(future.sourceId) })
    }


    @Test fun jodiFamilySequenceModelEntryPointExcludesTargetAndFutureRows() {
        val past = history()
        val future = row(Market.SRIDEVI, 100, open = "123", close = "456")
            .copy(sourceId = "future-sequence-row")
        val reports = ReverseEngineeringModel.scanJodiFamilySequences(
            past + future, Market.SRIDEVI, "main",
            LocalDate.of(2026, 3, 1), maximumWindowLength = 3
        )
        assertEquals(1, reports.size)
        assertEquals(40, reports.single().acceptedRecords)
        assertFalse(reports.single().windows.any {
            it.sourceIds.contains("future-sequence-row")
        })
    }

    @Test fun crossMarketSequenceModelEntryPointUsesOnlyEarlierDates() {
        val past = row(Market.SRIDEVI, 1, open = "166", close = "277")
        val future = row(Market.KALYAN, 100, open = "166", close = "277").copy(sourceId = "future-cross-market")
        val sameDatePrior = past.copy(market = Market.KALYAN, sourceId = "kalyan-prior")
        val result = ReverseEngineeringModel.scanCrossMarketAlignment(
            listOf(past, sameDatePrior, future),
            LocalDate.of(2026, 2, 1)
        )
        assertEquals(1, result.size)
        assertTrue(result.single().jodiMatch)
        assertFalse(result.any { it.sourceIds.contains(future.sourceId) })
    }


    @Test fun directionalJodiScanUsesOnlyEarlierChartRows() {
        val past = listOf(
            row(Market.SRIDEVI, 1, open = "001", close = "002"),
            row(Market.SRIDEVI, 2, open = "003", close = "004"),
            row(Market.SRIDEVI, 3, open = "005", close = "006")
        )
        val future = row(Market.SRIDEVI, 100, open = "007", close = "008")
            .copy(sourceId = "future-jodi")
        val reports = ReverseEngineeringModel.scanDirectionalJodiPatterns(
            past + future, Market.SRIDEVI, "main",
            LocalDate.of(2026, 1, 5), minimumLength = 2, maximumStep = 1
        )
        assertEquals(1, reports.size)
        assertEquals(3, reports.single().acceptedRecords)
        assertFalse(reports.single().occurrences.any {
            it.sourceIds.contains("future-jodi")
        })
    }


    @Test fun rankingIsIsolatedByMarketAndSession() {
        val mainHistory = history(Market.SRIDEVI)
        val otherSession = (0 until 40).map {
            row(Market.SRIDEVI, it, open = "999", close = "000", session = "late")
        }
        val otherMarket = (0 until 40).map {
            row(Market.KALYAN, it, open = "888", close = "111", session = "main")
        }
        val target = LocalDate.of(2026, 3, 1)
        val baseline = ReverseEngineeringModel.rank(
            mainHistory, Market.SRIDEVI, "main", target,
            ReverseEngineeringSide.OPEN, minimumHistory = 30, limit = 20
        )
        val combined = ReverseEngineeringModel.rank(
            mainHistory + otherSession + otherMarket, Market.SRIDEVI, "main", target,
            ReverseEngineeringSide.OPEN, minimumHistory = 30, limit = 20
        )

        assertEquals(40, combined.trainingRecords)
        assertEquals(
            baseline.candidates.map { it.panna to it.score },
            combined.candidates.map { it.panna to it.score }
        )
        assertEquals(baseline.familyCandidates, combined.familyCandidates)
    }

}
