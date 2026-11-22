package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenAnkRankingTest {
    private fun candidate(panna: String, score: Double): ReverseEngineeringCandidate {
        val ank = panna.sumOf { it.digitToInt() } % 10
        return ReverseEngineeringCandidate(
            panna, "000", PannaType.SP, ank, score,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            null, null, null, null
        )
    }

    @Test fun returnsAtMostFiveUniqueAnksRankedByCombinedPannaSupport() {
        val rows = listOf(
            candidate("100", 90.0), candidate("010", 80.0), candidate("001", 70.0),
            candidate("200", 60.0), candidate("020", 50.0), candidate("002", 40.0),
            candidate("300", 30.0), candidate("400", 20.0), candidate("500", 10.0),
            candidate("600", 5.0), candidate("700", 4.0), candidate("800", 3.0),
            candidate("900", 2.0)
        )
        val ranked = OpenAnkRanking.rank(rows)
        assertEquals(5, ranked.size)
        assertEquals(5, ranked.map { it.ank }.distinct().size)
        assertEquals(1, ranked.first().ank)
        assertEquals(3, ranked.first().supportingPannas)
        assertEquals(240.0, ranked.first().score, 0.0)
    }

    @Test fun emptyEvidenceReturnsNoAnks() {
        assertEquals(emptyList<OpenAnkCandidate>(), OpenAnkRanking.rank(emptyList()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMoreThanTenRequestedAnks() {
        OpenAnkRanking.rank(emptyList(), limit = 11)
    }
}
