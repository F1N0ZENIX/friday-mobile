package com.friday.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.friday.ai.command.LockPolicy
import com.friday.ai.data.local.dao.UserPreferenceDao
import com.friday.ai.data.local.entity.UserPreferenceEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the owner lets Friday do while the phone is locked ([LockPolicy.Level]). */
class LockLevelViewModel(private val prefs: UserPreferenceDao) : ViewModel() {

    private val _level = MutableStateFlow(LockPolicy.Level.VOICE)
    val level: StateFlow<LockPolicy.Level> = _level.asStateFlow()

    init {
        viewModelScope.launch { _level.value = LockPolicy.Level.of(prefs.get(LockPolicy.Level.PREF)) }
    }

    fun choose(level: LockPolicy.Level) {
        _level.value = level
        viewModelScope.launch { prefs.set(UserPreferenceEntity(LockPolicy.Level.PREF, level.key)) }
    }
}
