package com.relab.core

import java.time.LocalDate

enum class ChartPatternSide { OPEN_PANNA, CLOSE_PANNA, JODI }

enum class PatternDirection(val rowDelta: Int, val columnDelta: Int) {
    HORIZONTAL(0, 1),
    HORIZONTAL_REVERSE(0, -1),
    VERTICAL(1, 0),
    VERTICAL_REVERSE(-1, 0),
    DIAGONAL_DOWN_RIGHT(1, 1),
    DIAGONAL_UP_LEFT(-1, -1),
    DIAGONAL_DOWN_LEFT(1, -1),
    DIAGONAL_UP_RIGHT(-1, 1)
}

data class PatternGridCoordinate(val date: LocalDate, val position: Int)

data class DirectionalPatternOccurrence(
    val pattern: String,
    val direction: PatternDirection,
    val step: Int,
    val start: PatternGridCoordinate,
    val cells: List<PatternGridCoordinate>,
    val sourceIds: List<String>
)

data class DirectionalPatternReport(
    val market: Market,
    val sessionId: String,
    val side: ChartPatternSide,
    val acceptedRecords: Int,
    val rejectedRecords: Int,
    val occurrences: List<DirectionalPatternOccurrence>
)

/**
 * Scans Panna/Jodi digits across eight grid directions. Rows are the supplied
 * chronological observations and columns are digit positions. Invalid rows
 * remain as empty barriers, so a pattern cannot silently jump over malformed
 * source data. Calendar gaps between supplied observations are retained as
 * adjacent observed rows; no synthetic dates or values are introduced.
 *
 * This is descriptive pattern discovery, not a prediction or probability claim.
 */
object ChartPatternGridScanner {
    private data class Cell(val value: Char, val sourceId: String)

    fun scan(
        records: List<ChartRecord>,
        side: ChartPatternSide,
        minimumLength: Int = 3,
        maximumStep: Int = 2
    ): List<DirectionalPatternReport> {
        require(minimumLength >= 2) { "minimumLength must be at least 2" }
        require(maximumStep >= 1) { "maximumStep must be at least 1" }

        return records.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .map { (identity, rows) ->
                val orderedRows = rows.sortedBy { it.date }
                require(orderedRows.map { it.date }.distinct().size == orderedRows.size) {
                    "Duplicate dates for ${identity.first}/${identity.second}: reconcile source observations before scanning"
                }

                val orderedDates = orderedRows.map { it.date }
                val dateIndexes = orderedDates.withIndex().associate { it.value to it.index }
                val valid = orderedRows.filter { ChartMath.validate(it) is ValidationResult.Valid }
                val cells = mutableMapOf<PatternGridCoordinate, Cell>()
                valid.forEach { record ->
                    val chartValue = when (side) {
                        ChartPatternSide.OPEN_PANNA -> record.openPanna
                        ChartPatternSide.CLOSE_PANNA -> record.closePanna
                        ChartPatternSide.JODI -> record.jodi
                    }
                    chartValue.forEachIndexed { position, digit ->
                        cells[PatternGridCoordinate(record.date, position)] = Cell(digit, record.sourceId)
                    }
                }

                fun coordinateAt(rowIndex: Int, position: Int): PatternGridCoordinate? {
                    if (rowIndex !in orderedDates.indices) return null
                    val candidate = PatternGridCoordinate(orderedDates[rowIndex], position)
                    return candidate.takeIf { it in cells }
                }

                val occurrences = mutableListOf<DirectionalPatternOccurrence>()
                for ((coordinate, _) in cells) {
                    val rowIndex = dateIndexes.getValue(coordinate.date)
                    for (direction in PatternDirection.entries) {
                        for (step in 1..maximumStep) {
                            val predecessor = coordinateAt(
                                rowIndex - direction.rowDelta * step,
                                coordinate.position - direction.columnDelta * step
                            )
                            if (predecessor != null) continue

                            val path = mutableListOf<PatternGridCoordinate>()
                            val values = StringBuilder()
                            val sourceIds = mutableListOf<String>()
                            var cursorIndex = rowIndex
                            var cursorPosition = coordinate.position
                            while (true) {
                                val cursor = coordinateAt(cursorIndex, cursorPosition) ?: break
                                val cell = cells.getValue(cursor)
                                path += cursor
                                values.append(cell.value)
                                sourceIds += cell.sourceId
                                cursorIndex += direction.rowDelta * step
                                cursorPosition += direction.columnDelta * step
                            }
                            if (path.size >= minimumLength) {
                                occurrences += DirectionalPatternOccurrence(
                                    pattern = values.toString(),
                                    direction = direction,
                                    step = step,
                                    start = coordinate,
                                    cells = path.toList(),
                                    sourceIds = sourceIds.toList()
                                )
                            }
                        }
                    }
                }

                DirectionalPatternReport(
                    market = identity.first,
                    sessionId = identity.second,
                    side = side,
                    acceptedRecords = valid.size,
                    rejectedRecords = orderedRows.size - valid.size,
                    occurrences = occurrences.distinct().sortedWith(
                        compareBy<DirectionalPatternOccurrence> { it.direction.name }
                            .thenBy { it.start.date }
                            .thenBy { it.start.position }
                            .thenBy { it.step }
                            .thenBy { it.pattern }
                    )
                )
            }
    }
}
