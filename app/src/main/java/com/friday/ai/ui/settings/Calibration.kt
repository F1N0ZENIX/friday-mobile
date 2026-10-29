package com.friday.ai.ui.settings

import com.friday.ai.service.VoiceCalibrator
import com.friday.ai.service.VoiceCalibrator.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One calibration run at a time for the settings screen.
 *
 * Thresholds are saved only from a [Outcome.Completed] run; a silent,
 * cancelled or microphone-less run leaves the previous ones in place and
 * says why, with what to do next.
 */
class Calibration(
    private val scope: CoroutineScope,
    private val newCalibrator: () -> VoiceCalibrator,
    private val save: suspend (VoiceCalibrator.CalibrationResult) -> Unit
) {

    private val _phase = MutableStateFlow<VoiceCalibrator.Phase?>(null)
    val phase: StateFlow<VoiceCalibrator.Phase?> = _phase.asStateFlow()

    private val _energy = MutableStateFlow(0.0)
    val energy: StateFlow<Double> = _energy.asStateFlow()

    private val _result = MutableStateFlow<VoiceCalibrator.CalibrationResult?>(null)
    val result: StateFlow<VoiceCalibrator.CalibrationResult?> = _result.asStateFlow()

    /** Why the last run kept the old thresholds; null after a success or while running. */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var job: Job? = null

    /** Starts a run unless one is going; false when one already is. */
    fun start(): Boolean {
        if (job?.isActive == true) return false
        _message.value = null
        _phase.value = VoiceCalibrator.Phase.MEASURING_SILENCE
        val calibrator = newCalibrator()
        calibrator.listener = object : VoiceCalibrator.Listener {
            override fun onPhase(phase: VoiceCalibrator.Phase) {
                _phase.value = phase
            }

            override fun onEnergyUpdate(energy: Double) {
                _energy.value = energy
            }
        }
        job = scope.launch {
            try {
                val outcome = calibrator.calibrate()
                if (outcome is Outcome.Completed) {
                    _result.value = outcome.result
                    save(outcome.result)
                }
                _message.value = messageFor(outcome)
            } catch (e: CancellationException) {
                _message.value = CANCELLED
                throw e
            } finally {
                _phase.value = null
            }
        }
        return true
    }

    /** Stops a run; the microphone is freed and nothing is saved. */
    fun cancel() {
        job?.cancel()
    }

    companion object {
        const val CANCELLED = "Calibração cancelada. As configurações anteriores foram mantidas."
        const val TIMED_OUT = "Nenhuma voz detectada em 10 segundos. O microfone foi liberado. " +
            "Toque novamente e diga a frase em voz alta."
        const val NO_MICROPHONE = "Microfone indisponível. Conceda permissão ou " +
            "encerre a chamada ou gravação em outro aplicativo e tente novamente."

        fun messageFor(outcome: Outcome): String? = when (outcome) {
            is Outcome.Completed -> null
            Outcome.TimedOut -> TIMED_OUT
            Outcome.AudioUnavailable -> NO_MICROPHONE
        }
    }
}
