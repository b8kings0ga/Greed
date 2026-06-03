package com.excp.podroid.ui.screens.launcher

import android.app.ActivityManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.excp.podroid.data.repository.SettingsRepository
import com.excp.podroid.engine.VmEngine
import com.excp.podroid.engine.VmState
import com.excp.podroid.service.PodroidService
import com.excp.podroid.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val vmState: StateFlow<VmState> = engine.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, VmState.Idle)

    val bootStage: StateFlow<String> = engine.bootStage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val consoleText: StateFlow<String> = engine.consoleText
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val sshEnabled: StateFlow<Boolean> = settingsRepository.sshEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val lastBootDurationMs: StateFlow<Long> = settingsRepository.lastBootDurationMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val uptimeTicker: StateFlow<Long> = engine.state
        .map { it is VmState.Running }
        .flatMapLatest { isRunning ->
            if (isRunning) {
                flow {
                    while (true) {
                        emit(System.currentTimeMillis() / 1000)
                        delay(1000)
                    }
                }
            } else {
                flowOf(0L)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private var autoStartIssued = false
    private var fallbackRunningSinceMs: Long? = null

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

    val phoneIp: String by lazy { NetworkUtils.localIpv4(context) }

    fun sshCommand(): String? {
        if (!sshEnabled.value) return null
        return "ssh root@${phoneIp} -p 9922"
    }

    fun bootDurationLabel(): String? {
        val durationMs = lastBootDurationMs.value
        if (durationMs <= 0L) return null
        val totalSec = durationMs / 1000.0
        return String.format(java.util.Locale.US, "%.1fs", totalSec)
    }

    fun uptimeLabel(@Suppress("UNUSED_PARAMETER") tickerTrigger: Long): String? {
        val since = engine.runningSinceMs ?: fallbackRunningSinceMs ?: return null
        val totalSec = ((System.currentTimeMillis() - since) / 1000).coerceAtLeast(0)
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "Up ${hours}h ${minutes}m"
            minutes > 0 -> "Up ${minutes}m ${seconds}s"
            else -> "Up ${seconds}s"
        }
    }

    fun autoResourcesLabel(): String {
        val cpus = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalMb = (mi.totalMem / (1024L * 1024L)).toInt().coerceAtLeast(512)
        val hostReserveMb = maxOf(1536, totalMb / 8)
        val ramMb = minOf(8192, totalMb - hostReserveMb).coerceAtLeast(512)
        val ramLabel = if (ramMb >= 1024) "${ramMb / 1024} GB" else "$ramMb MB"
        return "$ramLabel · $cpus CPU"
    }

    init {
        viewModelScope.launch {
            var lastWasRunning = false
            engine.state.collect { state ->
                val nowRunning = state is VmState.Running
                if (nowRunning && !lastWasRunning && engine.runningSinceMs == null) {
                    fallbackRunningSinceMs = System.currentTimeMillis()
                }
                if (!nowRunning) {
                    fallbackRunningSinceMs = null
                }
                lastWasRunning = nowRunning
            }
        }
    }
}
