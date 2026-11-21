package com.relab.core

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MultiModelPredictionEngineTest {
    private fun row(day: Int, open: String, close: String = "444"): ChartRecord {
        val jodi = "${open.sumOf { it.digitToInt() } % 10}${close.sumOf { it.digitToInt() } % 10}"
        return ChartRecord(Market.SRIDEVI, LocalDate.of(2026, 1, 1).plusDays(day.toLong()),
            open, jodi, close, "source-$day")
    }

    @Test fun defaultEngineCombinesDistinctModelsAndProducesConsensus() {
        val rows = (0 until 20).map { row(it, if (it % 2 == 0) "111" else "222") }
        val result = MultiModelPredictionEngine().predict(rows, Market.SRIDEVI, "main",
            LocalDate.of(2026, 1, 21), PredictionChannel.OPEN_PANNA)
        assertEquals(20, result.trainingRows)
        assertEquals(3, result.modelPredictions.size)
        assertEquals(setOf("exact-frequency", "recency-weighted-frequency", "previous-result-transition"),
            result.modelPredictions.map { it.modelId }.toSet())
        assertFalse(result.consensus.abstained)
        assertTrue(result.consensus.candidates.isNotEmpty())
        assertTrue(result.note.contains("not probability"))
    }

    @Test fun futureAndTargetRowsCannotChangeEarlierPrediction() {
        val history = (0 until 12).map { row(it, if (it % 2 == 0) "111" else "222") }
        val target = LocalDate.of(2026, 1, 13)
        val engine = MultiModelPredictionEngine()
        val before = engine.predict(history, Market.SRIDEVI, "main", target, PredictionChannel.OPEN_PANNA)
        val withFuture = engine.predict(history + row(12, "999") + row(13, "888"),
            Market.SRIDEVI, "main", target, PredictionChannel.OPEN_PANNA)
        assertEquals(before.modelPredictions, withFuture.modelPredictions)
        assertEquals(before.consensus.candidates, withFuture.consensus.candidates)
    }

    @Test fun insufficientHistoryAbstainsInsteadOfGuessing() {
        val result = MultiModelPredictionEngine(minimumHistory = 6).predict(
            (0 until 5).map { row(it, "111") }, Market.SRIDEVI, "main",
            LocalDate.of(2026, 1, 6), PredictionChannel.OPEN_PANNA
        )
        assertTrue(result.consensus.abstained)
        assertTrue(result.modelPredictions.isEmpty())
        assertTrue(result.note.contains("need 6"))
    }

    @Test fun duplicateDatesAndDuplicateModelIdsAreRejected() {
        val duplicate = row(0, "111")
        assertThrows(IllegalArgumentException::class.java) {
            MultiModelPredictionEngine().predict(listOf(duplicate, duplicate.copy(sourceId = "other")),
                Market.SRIDEVI, "main", LocalDate.of(2026, 2, 1), PredictionChannel.OPEN_PANNA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MultiModelPredictionEngine(models = listOf(ExactFrequencyModel(), ExactFrequencyModel()))
        }
    }
}
