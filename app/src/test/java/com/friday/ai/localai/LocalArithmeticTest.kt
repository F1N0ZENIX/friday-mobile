package com.friday.ai.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalArithmeticTest {
    @Test
    fun `answers standalone arithmetic with Portuguese formatting`() {
        assertEquals("O resultado é 2.", LocalArithmetic.reply("quanto é 1+1?"))
        assertEquals("O resultado é 2.", LocalArithmetic.reply("calcule 1+1"))
        assertEquals("O resultado é 14.", LocalArithmetic.reply("2 + 3 * 4"))
        assertEquals("O resultado é 20.", LocalArithmetic.reply("(2 + 3) * 4"))
        assertEquals("O resultado é 1,5.", LocalArithmetic.reply("3 / 2"))
        assertEquals("O resultado é 2,75.", LocalArithmetic.reply("1,5 + 1.25"))
        assertEquals("O resultado é 3.", LocalArithmetic.reply("-2 + 5"))
    }

    @Test
    fun `handles division by zero only for complete expressions`() {
        assertEquals("Não é possível dividir por zero.", LocalArithmetic.reply("quanto é 1 / 0?"))
        assertEquals("Não é possível dividir por zero.", LocalArithmetic.reply("0 / (3 - 3)"))
        assertNull(LocalArithmetic.reply("1 / 0 explique"))
        assertNull(LocalArithmetic.reply("1 / 0 +"))
        assertNull(LocalArithmetic.reply("(1 / 0"))
    }

    @Test
    fun `only accepts complete standalone expressions`() {
        assertNull(LocalArithmetic.reply("explique quanto é 1+1"))
        assertNull(LocalArithmetic.reply("não calcule 1+1"))
        assertNull(LocalArithmetic.reply("1 + 1 porque é fácil"))
        assertNull(LocalArithmetic.reply("quanto é 1 +"))
        assertNull(LocalArithmetic.reply("quanto é (1 + 2"))
        assertNull(LocalArithmetic.reply("1 + 1? agora"))
        assertNull(LocalArithmetic.reply("\"1 + 1\""))
        assertNull(LocalArithmetic.reply("'1 + 1'"))
        assertNull(LocalArithmetic.reply("não 1 + 1"))
    }

    @Test
    fun `rejects malformed decimals and inputs beyond parser limits`() {
        assertNull(LocalArithmetic.reply("1. + 2"))
        assertNull(LocalArithmetic.reply("1,2.3 + 4"))
        assertNull(LocalArithmetic.reply("1+".repeat(60) + "1"))
        assertNull(LocalArithmetic.reply("-".repeat(49) + "1"))
        assertNull(LocalArithmetic.reply("1".repeat(19)))
        assertNull(LocalArithmetic.reply("(".repeat(13) + "1" + ")".repeat(13)))
    }
}
