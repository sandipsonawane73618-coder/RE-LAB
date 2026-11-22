package com.relab.core

/**
 * Joint open/close panna audit ("Sangam").
 *
 * Pair scores are derived from the two frozen side rankings and are relative evidence,
 * not probabilities. Only an exact open+close panna pair is an exact Sangam hit.
 */
data class SangamPairCandidate(
    val openPanna: String,
    val closePanna: String,
    val score: Double,
    val openRank: Int,
    val closeRank: Int,
    val openAnk: Int,
    val closeAnk: Int,
    val jodi: String,
    val openFamilySequence: String,
    val closeFamilySequence: String
)

data class SangamOutcomeAuditReport(
    val actualOpenPanna: String,
    val actualClosePanna: String,
    val actualOpenAnk: Int,
    val actualCloseAnk: Int,
    val actualJodi: String,
    val exactPairRank: Int?,
    val exactPairHit: Boolean,
    val openFamilySequenceMatchCount: Int,
    val closeFamilySequenceMatchCount: Int,
    val jodiMatchCount: Int,
    val rankedPairs: List<SangamPairCandidate>,
    val diagnosticNote: String
)

object SangamOutcomeAuditor {
    fun analyze(
        actualOpenPanna: String,
        actualClosePanna: String,
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>,
        pairLimit: Int = 100
    ): SangamOutcomeAuditReport {
        require(actualOpenPanna.matches(Regex("\\d{3}"))) { "Actual open panna must contain exactly three digits" }
        require(actualClosePanna.matches(Regex("\\d{3}"))) { "Actual close panna must contain exactly three digits" }
        require(pairLimit in 1..10_000) { "Pair limit must be between 1 and 10000" }
        validateCandidates(openCandidates, "open")
        validateCandidates(closeCandidates, "close")

        val actualOpenAnk = ank(actualOpenPanna)
        val actualCloseAnk = ank(actualClosePanna)
        val actualOpenFamily = family(actualOpenPanna)
        val actualCloseFamily = family(actualClosePanna)
        val pairs = openCandidates.mapIndexed { openIndex, open ->
            closeCandidates.mapIndexed { closeIndex, close ->
                val openAnk = ank(open.panna)
                val closeAnk = ank(close.panna)
                SangamPairCandidate(
                    openPanna = open.panna,
                    closePanna = close.panna,
                    score = open.score * close.score / 100.0,
                    openRank = openIndex + 1,
                    closeRank = closeIndex + 1,
                    openAnk = openAnk,
                    closeAnk = closeAnk,
                    jodi = "$openAnk$closeAnk",
                    openFamilySequence = family(open.panna),
                    closeFamilySequence = family(close.panna)
                )
            }
        }.flatten().sortedWith(
            compareByDescending<SangamPairCandidate> { it.score }
                .thenBy { it.openRank }
                .thenBy { it.closeRank }
                .thenBy { it.openPanna }
                .thenBy { it.closePanna }
        )

        val exactRank = pairs.indexOfFirst {
            it.openPanna == actualOpenPanna && it.closePanna == actualClosePanna
        }.takeIf { it >= 0 }?.plus(1)

        return SangamOutcomeAuditReport(
            actualOpenPanna = actualOpenPanna,
            actualClosePanna = actualClosePanna,
            actualOpenAnk = actualOpenAnk,
            actualCloseAnk = actualCloseAnk,
            actualJodi = "$actualOpenAnk$actualCloseAnk",
            exactPairRank = exactRank,
            exactPairHit = exactRank != null && exactRank <= pairLimit,
            openFamilySequenceMatchCount = openCandidates.count { family(it.panna) == actualOpenFamily },
            closeFamilySequenceMatchCount = closeCandidates.count { family(it.panna) == actualCloseFamily },
            jodiMatchCount = pairs.count { it.jodi == "$actualOpenAnk$actualCloseAnk" },
            rankedPairs = pairs.take(pairLimit),
            diagnosticNote = "Sangam is audited as the joint exact open+close panna pair. Jodi and family-sequence matches are separate diagnostics, not exact Sangam hits or calibrated probabilities. Pair scores are products of relative side scores."
        )
    }

    /**
     * Computes the exact pair rank without materializing and sorting the full
     * Cartesian product. This allows a 1,000 x 1,000 candidate universe while
     * retaining the same score and deterministic tie-break semantics as analyze().
     */
    fun exactPairRank(
        actualOpenPanna: String,
        actualClosePanna: String,
        openCandidates: List<ReverseEngineeringCandidate>,
        closeCandidates: List<ReverseEngineeringCandidate>
    ): Int? {
        require(actualOpenPanna.matches(Regex("\\d{3}"))) { "Actual open panna must contain exactly three digits" }
        require(actualClosePanna.matches(Regex("\\d{3}"))) { "Actual close panna must contain exactly three digits" }
        validateCandidates(openCandidates, "open")
        validateCandidates(closeCandidates, "close")
        val actualOpenIndex = openCandidates.indexOfFirst { it.panna == actualOpenPanna }
        val actualCloseIndex = closeCandidates.indexOfFirst { it.panna == actualClosePanna }
        if (actualOpenIndex < 0 || actualCloseIndex < 0) return null

        val actualScore = openCandidates[actualOpenIndex].score *
            closeCandidates[actualCloseIndex].score / 100.0
        val closeByScore = closeCandidates.withIndex().sortedWith(
            compareByDescending<IndexedValue<ReverseEngineeringCandidate>> { it.value.score }
                .thenBy { it.index }
        )
        var rank = 1L
        openCandidates.forEachIndexed { openIndex, open ->
            var low = 0
            var high = closeByScore.size
            while (low < high) {
                val middle = (low + high) ushr 1
                val score = open.score * closeByScore[middle].value.score / 100.0
                if (score > actualScore) low = middle + 1 else high = middle
            }
            rank += low.toLong()
            var index = low
            while (index < closeByScore.size) {
                val closeIndex = closeByScore[index].index
                val score = open.score * closeByScore[index].value.score / 100.0
                if (score != actualScore) break
                if (openIndex < actualOpenIndex ||
                    (openIndex == actualOpenIndex && closeIndex < actualCloseIndex)
                ) rank++
                index++
            }
        }
        return rank.toInt()
    }

    private fun validateCandidates(candidates: List<ReverseEngineeringCandidate>, side: String) {
        require(candidates.map { it.panna }.distinct().size == candidates.size) {
            "Duplicate $side candidate panna values are not allowed"
        }
        candidates.forEach {
            require(it.panna.matches(Regex("\\d{3}"))) { "Each $side candidate must contain exactly three digits" }
            require(it.score.isFinite() && it.score >= 0.0) { "Each $side candidate score must be finite and non-negative" }
        }
    }

    private fun ank(panna: String): Int = panna.sumOf { it.digitToInt() } % 10
    private fun family(panna: String): String =
        panna.map { ChartMath.family(it.digitToInt()).toString() }.joinToString("")
}
