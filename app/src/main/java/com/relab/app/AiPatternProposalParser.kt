package com.relab.app

import com.google.gson.JsonParser
import com.relab.core.Market
import com.relab.core.PatternEvidenceChannel
import com.relab.core.PatternOutcomeMode
import com.relab.core.ResearchPatternProposal

data class AiProposalParseResult(
    val proposals: List<ResearchPatternProposal>,
    val issues: List<String>
)

/** Accepts only the explicit machine-readable proposal block; prose is never guessed into a test. */
object AiPatternProposalParser {
    private const val START = "RELAB_PROPOSALS_JSON"
    private const val END = "END_RELAB_PROPOSALS_JSON"
    private const val MAX_PROPOSALS = 50

    fun parse(synthesis: String): AiProposalParseResult {
        val start = synthesis.indexOf(START)
        if (start < 0) return AiProposalParseResult(emptyList(), listOf("AI output did not include the required proposal block"))
        val contentStart = start + START.length
        val end = synthesis.indexOf(END, contentStart)
        if (end < 0) return AiProposalParseResult(emptyList(), listOf("AI proposal block is missing its closing marker"))
        val json = synthesis.substring(contentStart, end).trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val array = try {
            JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
                ?: return AiProposalParseResult(emptyList(), listOf("AI proposal block must be a JSON array"))
        } catch (error: Exception) {
            return AiProposalParseResult(emptyList(), listOf("AI proposal JSON is invalid: ${error.message ?: "parse error"}"))
        }
        if (array.size() > MAX_PROPOSALS) {
            return AiProposalParseResult(emptyList(), listOf("AI proposed more than $MAX_PROPOSALS patterns; no proposals were accepted"))
        }

        val proposals = mutableListOf<ResearchPatternProposal>()
        val issues = mutableListOf<String>()
        array.forEachIndexed { index, element ->
            try {
                require(element.isJsonObject) { "entry must be an object" }
                val item = element.asJsonObject
                fun required(name: String): String {
                    val value = item.get(name)
                    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                        "missing string field '$name'"
                    }
                    return value.asString.trim().also { require(it.isNotBlank()) { "field '$name' is blank" } }
                }
                val id = required("id")
                val market = Market.valueOf(required("market"))
                val session = required("session")
                val channel = PatternEvidenceChannel.valueOf(required("channel"))
                val pattern = required("pattern")
                val mode = PatternOutcomeMode.valueOf(required("outcomeMode"))
                val rationale = item.get("rationale")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
                proposals += ResearchPatternProposal(
                    id = id, market = market, sessionId = session, channel = channel,
                    pattern = pattern, outcomeMode = mode, source = "AI", rationale = rationale
                )
            } catch (error: Exception) {
                issues += "Proposal ${index + 1} rejected: ${error.message ?: "invalid entry"}"
            }
        }
        val duplicateIds = proposals.groupBy { it.id }.filterValues { it.size > 1 }.keys
        val accepted = proposals.filterNot { it.id in duplicateIds }
        duplicateIds.forEach { issues += "Duplicate proposal id rejected: $it" }
        return AiProposalParseResult(accepted, issues)
    }
}
