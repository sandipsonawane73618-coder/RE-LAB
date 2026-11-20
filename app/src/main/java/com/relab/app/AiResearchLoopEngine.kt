package com.relab.app

/**
 * Bounded, resumable Researcher -> Auditor -> Reconciler cycles.
 *
 * Each round reuses the same immutable evidence pack. Prior AI outputs are carried forward
 * only as UNVERIFIED hypotheses; this loop never edits chart history or claims that a
 * generated recommendation has passed an executable backtest.
 */
data class AiResearchLoopRoundCheckpoint(
    val roundNumber: Int,
    val checkpoint: AiResearchCheckpoint = AiResearchCheckpoint()
)

data class AiResearchLoopCheckpoint(
    val rounds: List<AiResearchLoopRoundCheckpoint> = emptyList()
)

data class AiResearchLoopRoundResult(
    val roundNumber: Int,
    val result: AiResearchResult
)

data class AiResearchLoopResult(
    val rounds: List<AiResearchLoopRoundResult>,
    val verificationState: String = UNVERIFIED
) {
    val finalSynthesis: String
        get() = rounds.lastOrNull()?.result?.finalSynthesis.orEmpty()

    fun report(): String = buildString {
        append("AUTO RESEARCH LOOP — ${rounds.size} completed round(s)\n")
        append("Verification state: $verificationState\n")
        append("AI agreement is not independent experimental verification.\n\n")
        rounds.forEach { round ->
            append("===== ROUND ${round.roundNumber} =====\n")
            append("RESEARCHER (Gemini)\n${round.result.geminiAnalysis}\n\n")
            append("AUDITOR (Groq)\n${round.result.groqReview}\n\n")
            append("IMPROVED SYNTHESIS (Gemini)\n${round.result.finalSynthesis}\n\n")
        }
        append("FINAL STATE: $verificationState. No chart records were modified.")
    }

    companion object { const val UNVERIFIED = "UNVERIFIED" }
}

class AiResearchLoopEngine(
    private val runRound: (
        geminiKey: String,
        groqKey: String,
        question: String,
        evidence: String,
        priorMemory: String,
        checkpoint: AiResearchCheckpoint,
        onCheckpoint: (stage: String, output: String) -> Unit
    ) -> AiResearchResult = { geminiKey, groqKey, question, evidence, priorMemory, checkpoint, onCheckpoint ->
        AiResearchEngine().runCrossReview(
            geminiKey, groqKey, question, evidence, priorMemory, checkpoint, onCheckpoint
        )
    }
) {
    fun run(
        geminiKey: String,
        groqKey: String,
        question: String,
        evidence: String,
        priorMemory: String,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        checkpoint: AiResearchLoopCheckpoint = AiResearchLoopCheckpoint(),
        onCheckpoint: (roundNumber: Int, stage: String, output: String) -> Unit = { _, _, _ -> }
    ): AiResearchLoopResult {
        require(maxRounds in 1..MAX_ALLOWED_ROUNDS) { "Auto Research Loop supports 1 to $MAX_ALLOWED_ROUNDS rounds" }
        require(geminiKey.isNotBlank() && groqKey.isNotBlank()) { "Save both Gemini and Groq API keys first" }
        require(question.isNotBlank()) { "Enter a research question" }
        require(evidence.isNotBlank()) { "Provide an evidence pack before starting research" }

        val saved = checkpoint.rounds.associateBy { it.roundNumber }
        require(saved.keys.all { it in 1..maxRounds }) { "Checkpoint contains a round outside the requested loop limit" }
        val completed = mutableListOf<AiResearchLoopRoundResult>()
        var accumulatedMemory = priorMemory.take(MAX_MEMORY_CHARS)

        for (round in 1..maxRounds) {
            val roundCheckpoint = saved[round]?.checkpoint ?: AiResearchCheckpoint()
            val roundQuestion = if (round == 1) question else buildString {
                append(question)
                append("\n\nAUTO-RESEARCH ROUND $round OF $maxRounds:")
                append(" Re-examine the same original evidence. Improve or reject the previous round's hypotheses.")
                append(" The Auditor must challenge the new reasoning independently. Identify which proposed tests")
                append(" can actually be executed outside this AI loop; do not claim tests were run unless results")
                append(" are present in the supplied evidence. Preserve unresolved disagreements.")
            }
            val result = runRound(
                geminiKey, groqKey, roundQuestion, evidence, accumulatedMemory, roundCheckpoint
            ) { stage, output -> onCheckpoint(round, stage, output) }
            completed += AiResearchLoopRoundResult(round, result)

            accumulatedMemory = buildString {
                if (accumulatedMemory.isNotBlank()) {
                    append(accumulatedMemory)
                    append("\n\n")
                }
                append("AUTO-RESEARCH ROUND $round — UNVERIFIED AI HYPOTHESES\n")
                append("Gemini: ")
                append(result.geminiAnalysis.take(MAX_STAGE_MEMORY_CHARS))
                append("\nGroq audit: ")
                append(result.groqReview.take(MAX_STAGE_MEMORY_CHARS))
                append("\nGemini synthesis: ")
                append(result.finalSynthesis.take(MAX_STAGE_MEMORY_CHARS))
            }.takeLast(MAX_MEMORY_CHARS)
        }
        return AiResearchLoopResult(completed)
    }

    companion object {
        const val DEFAULT_MAX_ROUNDS = 3
        const val MAX_ALLOWED_ROUNDS = 3
        private const val MAX_MEMORY_CHARS = 12_000
        private const val MAX_STAGE_MEMORY_CHARS = 3_000
    }
}
