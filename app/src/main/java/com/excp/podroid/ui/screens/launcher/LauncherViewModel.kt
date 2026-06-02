package com.excp.podroid.ui.screens.launcher

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.service.PodroidService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class LauncherViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VmEngine,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    val bootStage: StateFlow<String> = engine.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private var autoStartIssued = false

    fun ensureAutoStart() {
        if (autoStartIssued) return
        autoStartIssued = true
        when (engine.state.value) {
            is VmState.Starting, is VmState.Running -> Unit
            else -> PodroidService.start(context)
        }
    }

    fun startVm() = PodroidService.start(context)

    fun stopVm() = PodroidService.stop(context)

    fun restartVm() {
        PodroidService.stop(context)
        viewModelScope.launch {
            val reached = withTimeoutOrNull(10_000) {
                engine.state.first { state ->
                    state is VmState.Stopped || state is VmState.Idle || state is VmState.Error
                }
            }
            if (reached != null) {
                PodroidService.start(context)
            }
        }
    }
}
