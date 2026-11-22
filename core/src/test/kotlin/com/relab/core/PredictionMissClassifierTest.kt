package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PredictionMissClassifierTest {
    @Test fun classifiesPannaCandidateAndRankingMisses() {
        assertEquals(PredictionMissType.CANDIDATE_MISS, PredictionMissClassifier.panna(null))
        assertEquals(PredictionMissType.TOP_10_HIT, PredictionMissClassifier.panna(10))
        assertEquals(PredictionMissType.RANKING_MISS, PredictionMissClassifier.panna(11))
    }

    @Test fun classifiesHalfSangamComponentAndRankingMisses() {
        assertEquals(PredictionMissType.CANDIDATE_MISS, PredictionMissClassifier.combination(null, false, true))
        assertEquals(PredictionMissType.CANDIDATE_MISS, PredictionMissClassifier.combination(null, true, false))
        assertEquals(PredictionMissType.RANKING_MISS, PredictionMissClassifier.combination(null, true, true))
        assertEquals(PredictionMissType.TOP_10_HIT, PredictionMissClassifier.combination(10, true, true))
        assertEquals(PredictionMissType.RANKING_MISS, PredictionMissClassifier.combination(11, true, true))
    }
}
