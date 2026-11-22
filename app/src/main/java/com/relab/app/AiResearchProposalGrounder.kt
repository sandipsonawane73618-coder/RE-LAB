package com.relab.app

import com.relab.core.ChartMath
import com.relab.core.ChartRecord
import com.relab.core.FamilyMovementMath
import com.relab.core.PatternEvidenceChannel
import com.relab.core.PatternOutcomeMode
import com.relab.core.ResearchPatternProposal
import com.relab.core.ValidationResult
import java.time.LocalDate

data class ProposalGroundingRejection(val proposalId: String, val reason: String)

data class ProposalGroundingResult(
    val accepted: List<ResearchPatternProposal>,
    val rejected: List<ProposalGroundingRejection>
)

/**
 * Deterministic discovery gate for AI-generated hypotheses.
 * A proposal is accepted only when its complete sequence occurs in supplied
 * pre-cutoff chart rows for the exact market, session and channel. This proves
 * sequence presence only; predictive value requires the separate chronological backtest.
 */
object AiResearchProposalGrounder {
    fun ground(
        records: List<ChartRecord>,
        proposals: List<ResearchPatternProposal>,
        targetDateExclusive: LocalDate
    ): ProposalGroundingResult {
        val accepted = mutableListOf<ResearchPatternProposal>()
        val rejected = mutableListOf<ProposalGroundingRejection>()
        val duplicateIds = proposals.groupBy { it.id }.filterValues { it.size > 1 }.keys

        proposals.forEach { proposal ->
            fun reject(reason: String) { rejected += ProposalGroundingRejection(proposal.id, reason) }
            if (proposal.id.isBlank()) { reject("Proposal id is blank"); return@forEach }
            if (proposal.id in duplicateIds) { reject("Duplicate proposal id"); return@forEach }
            if (proposal.sessionId.isBlank()) { reject("Session id is blank"); return@forEach }
            val tokens = proposal.pattern.split(">")
            if (tokens.size !in 1..20 || tokens.any { it.isBlank() }) {
                reject("Pattern must contain 1 to 20 non-empty values"); return@forEach
            }
            val expectedLength = if (proposal.channel == PatternEvidenceChannel.JODI) 2 else 3
            if (tokens.any { token -> token.length != expectedLength || token.any { !it.isDigit() } }) {
                reject("Pattern values must each contain exactly $expectedLength digits"); return@forEach
            }

            val scoped = records.asSequence()
                .filter { it.market == proposal.market && it.sessionId == proposal.sessionId }
                .filter { it.date.isBefore(targetDateExclusive) }
                .sortedBy { it.date }
                .toList()
            if (scoped.isEmpty()) {
                reject("No pre-cutoff chart rows exist for ${proposal.market}/${proposal.sessionId}"); return@forEach
            }
            if (scoped.map { it.date }.distinct().size != scoped.size) {
                reject("Duplicate dates exist for ${proposal.market}/${proposal.sessionId}; reconcile before discovery"); return@forEach
            }

            fun value(record: ChartRecord): String = when (proposal.channel) {
                PatternEvidenceChannel.OPEN_PANNA -> record.openPanna
                PatternEvidenceChannel.CLOSE_PANNA -> record.closePanna
                PatternEvidenceChannel.JODI -> record.jodi
            }
            fun normalize(value: String): String = when (proposal.outcomeMode) {
                PatternOutcomeMode.EXACT -> value
                PatternOutcomeMode.FAMILY_MOVEMENT -> FamilyMovementMath.key(value)
            }
            val expected = tokens.map(::normalize)
            val values = scoped.map { record ->
                if (ChartMath.validate(record) is ValidationResult.Valid) normalize(value(record)) else null
            }
            val found = if (expected.size > values.size) false else
                (0..(values.size - expected.size)).any { start ->
                    val window = values.subList(start, start + expected.size)
                    window.all { it != null } && window == expected
                }
            if (found) accepted += proposal
            else reject("Proposed sequence does not occur in pre-cutoff rows for ${proposal.market}/${proposal.sessionId}/${proposal.channel}/${proposal.outcomeMode}")
        }
        return ProposalGroundingResult(accepted, rejected)
    }
}
