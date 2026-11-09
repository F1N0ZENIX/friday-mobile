package com.friday.ai.localai

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Bounded arithmetic evaluator for complete, standalone user calculations. */
object LocalArithmetic {
    private const val MAX_INPUT_LENGTH = 120
    private const val MAX_TOKENS = 48
    private const val MAX_NUMBER_DIGITS = 18
    private const val MAX_PARENTHESES_DEPTH = 12
    private val mathContext = MathContext(18, RoundingMode.HALF_UP)
    private val calculationPrefix = Regex(
        "^(?:quanto\\s+[ée]|calcule)\\s+",
        RegexOption.IGNORE_CASE
    )

    fun reply(prompt: String): String? {
        val expression = standaloneExpression(prompt) ?: return null
        try {
            SyntaxValidator(expression).validate()
        } catch (_: IllegalArgumentException) {
            return null
        }
        return try {
            "O resultado é ${format(ExpressionParser(expression).parse())}."
        } catch (_: DivisionByZeroException) {
            "Não é possível dividir por zero."
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun standaloneExpression(prompt: String): String? {
        if (prompt.length > MAX_INPUT_LENGTH) return null
        val trimmed = prompt.trim().removeSuffix("?").trim()
        if (trimmed.isEmpty()) return null
        val expression = calculationPrefix.replace(trimmed, "")
        if (expression.isEmpty() || expression.length > MAX_INPUT_LENGTH) return null
        return expression.takeIf(::containsOnlyExpressionCharacters)
    }

    private fun containsOnlyExpressionCharacters(expression: String): Boolean =
        expression.all { it.isDigit() || it.isWhitespace() || it in "+-*/().," }

    private fun format(number: BigDecimal): String {
        val normalized = if (number.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else number.stripTrailingZeros()
        return normalized.toPlainString().replace('.', ',')
    }

    private class SyntaxValidator(private val text: String) {
        private var position = 0
        private var tokenCount = 0
        private var parenthesesDepth = 0

        fun validate() {
            parseSum()
            skipWhitespace()
            require(position == text.length) { "Unexpected input" }
        }

        private fun parseSum() {
            parseProduct()
            while (true) {
                skipWhitespace()
                when {
                    consume('+') || consume('-') -> parseProduct()
                    else -> return
                }
            }
        }

        private fun parseProduct() {
            parseUnary()
            while (true) {
                skipWhitespace()
                when {
                    consume('*') || consume('/') -> parseUnary()
                    else -> return
                }
            }
        }

        private fun parseUnary() {
            skipWhitespace()
            if (consume('+') || consume('-')) parseUnary() else parsePrimary()
        }

        private fun parsePrimary() {
            skipWhitespace()
            if (consume('(')) {
                parenthesesDepth += 1
                require(parenthesesDepth <= MAX_PARENTHESES_DEPTH) { "Too deeply nested" }
                parseSum()
                skipWhitespace()
                require(consume(')')) { "Missing close parenthesis" }
                parenthesesDepth -= 1
            } else {
                parseNumber()
            }
        }

        private fun parseNumber() {
            skipWhitespace()
            var separatorSeen = false
            var digitCount = 0
            var digitsAfterSeparator = 0
            while (position < text.length) {
                when (text[position]) {
                    in '0'..'9' -> {
                        digitCount += 1
                        if (separatorSeen) digitsAfterSeparator += 1
                        position += 1
                    }
                    '.', ',' -> if (!separatorSeen && digitCount > 0) {
                        separatorSeen = true
                        position += 1
                    } else {
                        break
                    }
                    else -> break
                }
            }
            require(digitCount > 0) { "Expected number" }
            require(!separatorSeen || digitsAfterSeparator > 0) { "Invalid decimal" }
            require(digitCount <= MAX_NUMBER_DIGITS) { "Number too large" }
            countToken()
        }

        private fun consume(expected: Char): Boolean {
            if (position >= text.length || text[position] != expected) return false
            position += 1
            countToken()
            return true
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position += 1
        }

        private fun countToken() {
            tokenCount += 1
            require(tokenCount <= MAX_TOKENS) { "Too many tokens" }
        }
    }

    private class ExpressionParser(private val text: String) {
        private var position = 0

        fun parse(): BigDecimal {
            val result = parseSum()
            skipWhitespace()
            require(position == text.length) { "Unexpected input" }
            return result
        }

        private fun parseSum(): BigDecimal {
            var result = parseProduct()
            while (true) {
                skipWhitespace()
                result = when {
                    consume('+') -> result.add(parseProduct(), mathContext)
                    consume('-') -> result.subtract(parseProduct(), mathContext)
                    else -> return result
                }
            }
        }

        private fun parseProduct(): BigDecimal {
            var result = parseUnary()
            while (true) {
                skipWhitespace()
                result = when {
                    consume('*') -> result.multiply(parseUnary(), mathContext)
                    consume('/') -> divide(result, parseUnary())
                    else -> return result
                }
            }
        }

        private fun divide(dividend: BigDecimal, divisor: BigDecimal): BigDecimal {
            if (divisor.compareTo(BigDecimal.ZERO) == 0) throw DivisionByZeroException()
            return dividend.divide(divisor, mathContext)
        }

        private fun parseUnary(): BigDecimal {
            skipWhitespace()
            return when {
                consume('+') -> parseUnary()
                consume('-') -> parseUnary().negate(mathContext)
                else -> parsePrimary()
            }
        }

        private fun parsePrimary(): BigDecimal {
            skipWhitespace()
            if (consume('(')) return parseParenthesized()
            return parseNumber()
        }

        private fun parseParenthesized(): BigDecimal {
            val result = parseSum()
            skipWhitespace()
            require(consume(')')) { "Missing close parenthesis" }
            return result
        }

        private fun parseNumber(): BigDecimal {
            skipWhitespace()
            val start = position
            while (position < text.length && (text[position].isDigit() || text[position] in ".,")) position += 1
            require(position > start) { "Expected number" }
            return text.substring(start, position).replace(',', '.').toBigDecimal()
        }

        private fun consume(expected: Char): Boolean {
            if (position >= text.length || text[position] != expected) return false
            position += 1
            return true
        }

        private fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position += 1
        }
    }

    private class DivisionByZeroException : IllegalArgumentException()
}
