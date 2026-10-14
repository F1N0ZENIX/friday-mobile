package com.friday.ai.command

import com.friday.ai.domain.model.CommandResult

/**
 * What a locked phone lets through.
 *
 * The wake word works with the screen locked, so anyone in the room can say
 * "Пятница". What only changes the phone itself — music, alarms, the
 * flashlight, modes, weather — always runs. What reaches other people or
 * reads out what they wrote — calls, messages, replies, mail, missed
 * notifications, the owner's own sites — depends on the owner's choice in
 * settings ([Level]):
 *
 *  - [Level.SAFE_ONLY]: never while locked; the owner unlocks and asks again.
 *  - [Level.VOICE] (default): runs when the recorded voice profile vouches
 *    for the speaker; otherwise Android's own unlock screen is shown —
 *    fingerprint, face or PIN — and the command runs right after it.
 *  - [Level.UNLOCK]: always Android's unlock screen first, voice or not.
 *
 * Friday never sees or keeps the PIN: the unlock is Android's
 * (KeyguardManager.requestDismissKeyguard), and it either happens or not.
 */
object LockPolicy {

    enum class Level(val key: String) {
        SAFE_ONLY("safe_only"), VOICE("voice"), UNLOCK("unlock");

        companion object {
            const val PREF = "lock_level"
            fun of(key: String?): Level = entries.firstOrNull { it.key == key } ?: VOICE
        }
    }

    enum class Decision { RUN, ASK_UNLOCK, REFUSE }

    fun decide(command: CommandResult, locked: Boolean, ownerVerified: Boolean, level: Level): Decision = when {
        !locked || !personal(command) -> Decision.RUN
        level == Level.SAFE_ONLY -> Decision.REFUSE
        level == Level.VOICE && ownerVerified -> Decision.RUN
        else -> Decision.ASK_UNLOCK
    }

    /**
     * Why a command [decide]d on cannot run right now, or null when it
     * can — after Android's unlock screen, if that is what it takes.
     */
    suspend fun refusal(decision: Decision, unlocker: Unlocker, russian: Boolean): String? = when (decision) {
        Decision.RUN -> null
        Decision.REFUSE -> reply(russian)
        Decision.ASK_UNLOCK -> when (unlocker.unlock()) {
            Unlocker.Result.UNLOCKED -> null
            Unlocker.Result.STAYED_LOCKED -> notUnlocked(russian)
            Unlocker.Result.CANNOT_ASK -> cannotAsk(russian)
        }
    }

    /** Whether [command] must wait for the phone to be unlocked (the default level's view). */
    fun needsUnlock(command: CommandResult, locked: Boolean, ownerVerified: Boolean): Boolean =
        decide(command, locked, ownerVerified, Level.VOICE) != Decision.RUN

    /** Reaches other people, or reads out what they wrote. */
    fun personal(command: CommandResult): Boolean = when (command) {
        is CommandResult.PhoneCall, is CommandResult.SendMessage, is CommandResult.ReplyMessage,
        is CommandResult.ReadMessages, is CommandResult.Mail, CommandResult.WhatDidIMiss -> true
        // The owner's own sites: on a locked phone, only for the owner's voice.
        is CommandResult.OpenLink -> true
        is CommandResult.Sequence -> command.steps.any(::personal)
        else -> false
    }

    /** [Level.SAFE_ONLY]: the owner chose to do this only on an unlocked phone. */
    fun reply(russian: Boolean): String =
        if (russian) "На заблокированном телефоне я этого не делаю. Разблокируйте его и повторите."
        else "I don't do that on a locked phone. Unlock it and say it again."

    /** The unlock screen was shown and the phone stayed locked. */
    fun notUnlocked(russian: Boolean): String =
        if (russian) "Телефон не разблокирован — ничего не сделала."
        else "The phone wasn't unlocked, so I did nothing."

    /** The unlock screen could not be shown (full-screen notifications not allowed). */
    fun cannotAsk(russian: Boolean): String =
        if (russian) {
            "Не могу показать экран разблокировки: разрешите Friday полноэкранные уведомления в настройках. " +
                "А пока разблокируйте телефон и повторите."
        } else {
            "I can't show the unlock screen: allow Friday full-screen notifications in settings. " +
                "Meanwhile, unlock the phone and say it again."
        }
}
