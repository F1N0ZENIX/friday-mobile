package com.friday.ai.command

import com.friday.ai.core.CommandRouter
import com.friday.ai.core.mail.MailCommands
import com.friday.ai.domain.model.CommandResult
import com.friday.ai.domain.model.DeviceAction
import com.friday.ai.service.mail.MailAssistant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyTest {

    private val message = CommandResult.SendMessage("мама", "еду", null)

    @Test
    fun `what reaches people or reads their words waits for unlock without a verified voice`() {
        listOf(
            CommandResult.PhoneCall("мама"), message, CommandResult.ReplyMessage(null, "да"),
            CommandResult.ReadMessages(null), CommandResult.Mail(MailCommands.Request.CheckUnread),
            CommandResult.WhatDidIMiss
        ).forEach { assertTrue("$it", LockPolicy.needsUnlock(it, locked = true, ownerVerified = false)) }
    }

    @Test
    fun `the phone's own things don't`() {
        listOf(
            CommandResult.ToggleFlashlight, CommandResult.DeviceControl(DeviceAction.VOLUME_UP),
            CommandResult.SetTimer(60, null), CommandResult.Mode.Run("отдыха")
        ).forEach { assertFalse("$it", LockPolicy.needsUnlock(it, locked = true, ownerVerified = false)) }
    }

    @Test
    fun `unlocked, or the owner's voice verified, anything goes`() {
        assertFalse(LockPolicy.needsUnlock(message, locked = false, ownerVerified = false))
        assertFalse(LockPolicy.needsUnlock(message, locked = true, ownerVerified = true))
    }

    @Test
    fun `a sequence with one personal step waits`() {
        val s = CommandResult.Sequence(listOf(CommandResult.ToggleFlashlight, message))
        assertTrue(LockPolicy.needsUnlock(s, locked = true, ownerVerified = false))
    }

    @Test
    fun `each level decides as the owner chose`() {
        val d = LockPolicy::decide
        // Only safe: never personal while locked, voice or not.
        assertEquals(LockPolicy.Decision.REFUSE, d(message, true, true, LockPolicy.Level.SAFE_ONLY))
        // Voice: the profile vouches; without it, Android's unlock screen.
        assertEquals(LockPolicy.Decision.RUN, d(message, true, true, LockPolicy.Level.VOICE))
        assertEquals(LockPolicy.Decision.ASK_UNLOCK, d(message, true, false, LockPolicy.Level.VOICE))
        // Unlock: always the unlock screen first.
        assertEquals(LockPolicy.Decision.ASK_UNLOCK, d(message, true, true, LockPolicy.Level.UNLOCK))
        // Unlocked, or not personal: every level runs it.
        LockPolicy.Level.entries.forEach { level ->
            assertEquals(LockPolicy.Decision.RUN, d(message, false, false, level))
            assertEquals(LockPolicy.Decision.RUN, d(CommandResult.ToggleFlashlight, true, false, level))
        }
    }

    @Test
    fun `an unknown or missing setting is the default level`() {
        assertEquals(LockPolicy.Level.VOICE, LockPolicy.Level.of(null))
        assertEquals(LockPolicy.Level.VOICE, LockPolicy.Level.of("nonsense"))
        assertEquals(LockPolicy.Level.UNLOCK, LockPolicy.Level.of("unlock"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LockedExecutorTest {

    private val dispatcher = StandardTestDispatcher()
    private val phone = mockk<PhoneActions>(relaxed = true)
    private var locked = true
    private var level = LockPolicy.Level.VOICE
    private var unlockAnswer = Unlocker.Result.STAYED_LOCKED
    private var unlockAsked = 0

    private val executor = CommandExecutor(
        mockk<CommandRouter>(relaxed = true), phone, mockk(relaxed = true), mockk(relaxed = true),
        mockk<MailAssistant>(relaxed = true), dispatcher,
        lock = { locked to false },
        lockLevel = { level },
        unlocker = Unlocker {
            unlockAsked++
            if (unlockAnswer == Unlocker.Result.UNLOCKED) locked = false
            unlockAnswer
        }
    )

    private val message = CommandResult.SendMessage("мама", "еду", null)

    @Test
    fun `a locked phone without a voice profile asks Android to unlock, and sends nothing if it stays locked`() =
        runTest(dispatcher) {
            coEvery { phone.run(any(), any()) } returns "ok"
            val sent = executor.execute(message, russian = true) as CommandExecutor.Outcome.Reply
            assertEquals(1, unlockAsked)
            assertEquals("Телефон не разблокирован — ничего не сделала.", sent.text)
            coVerify(exactly = 0) { phone.run(message, any()) }

            assertEquals(CommandExecutor.Outcome.Reply("ok"), executor.execute(CommandResult.ToggleFlashlight, true))
            assertEquals("the torch needs no unlock", 1, unlockAsked)
        }

    @Test
    fun `after the owner unlocks, the command runs at once`() = runTest(dispatcher) {
        unlockAnswer = Unlocker.Result.UNLOCKED
        coEvery { phone.run(any(), any()) } returns "Отправить SMS? Да или нет."
        assertEquals(CommandExecutor.Outcome.Reply("Отправить SMS? Да или нет."), executor.execute(message, true))
        coVerify { phone.run(message, true) }
    }

    @Test
    fun `without full-screen notifications it says how to allow the unlock screen`() = runTest(dispatcher) {
        unlockAnswer = Unlocker.Result.CANNOT_ASK
        val r = executor.execute(message, true) as CommandExecutor.Outcome.Reply
        assertTrue(r.text, r.text.contains("полноэкранные уведомления"))
    }

    @Test
    fun `safe only never asks to unlock`() = runTest(dispatcher) {
        level = LockPolicy.Level.SAFE_ONLY
        val r = executor.execute(message, true) as CommandExecutor.Outcome.Reply
        assertTrue(r.text, r.text.startsWith("На заблокированном телефоне"))
        assertEquals(0, unlockAsked)
    }

    @Test
    fun `in a sequence only the personal step waits`() = runTest(dispatcher) {
        coEvery { phone.run(any(), any()) } returns "Фонарик включён"
        val r = executor.execute(
            CommandResult.Sequence(listOf(CommandResult.ToggleFlashlight, CommandResult.PhoneCall("мама"))), true
        ) as CommandExecutor.Outcome.Reply
        assertTrue(r.text, r.text.startsWith("Фонарик включён."))
        assertTrue(r.text.contains("не разблокирован"))
    }

    @Test
    fun `unlocked, it just runs`() = runTest(dispatcher) {
        locked = false
        coEvery { phone.run(any(), any()) } returns "Пишу маме"
        assertEquals(
            CommandExecutor.Outcome.Reply("Пишу маме"),
            executor.execute(CommandResult.SendMessage("мама", "еду", null), true)
        )
    }
}
