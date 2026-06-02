package com.excp.podroid.ui.screens.launcher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.excp.podroid.R
import com.excp.podroid.engine.VmState
import com.excp.podroid.ui.components.AdaptiveContainer
import com.excp.podroid.ui.components.PodroidDestructiveButton
import com.excp.podroid.ui.components.PodroidGhostButton
import com.excp.podroid.ui.components.PodroidPrimaryButton
import com.excp.podroid.ui.components.PodroidStatus
import com.excp.podroid.ui.components.PodroidStatusColors
import com.excp.podroid.ui.components.PodroidTopBar
import com.excp.podroid.ui.theme.PodroidTokens

@Composable
fun LauncherScreen(
    windowSizeClass: WindowSizeClass,
    onNavigateToSettings: () -> Unit,
    viewModel: LauncherViewModel = hiltViewModel(),
) {
    val vmState by viewModel.vmState.collectAsStateWithLifecycle()
    val bootStage by viewModel.bootStage.collectAsStateWithLifecycle()
    val isCompactHeight = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    LaunchedEffect(Unit) {
        viewModel.ensureAutoStart()
    }

    Scaffold(
        topBar = {
            PodroidTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        AdaptiveContainer(
            windowSizeClass = windowSizeClass,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            maxWidth = if (isCompactHeight) 900 else 600,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PodroidTokens.Spacing.XL, vertical = PodroidTokens.Spacing.XL),
                verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.LG),
            ) {
                LauncherStatusCard(vmState = vmState, bootStage = bootStage)
                LauncherActions(
                    vmState = vmState,
                    onStart = viewModel::startVm,
                    onStop = viewModel::stopVm,
                    onRestart = viewModel::restartVm,
                )
            }
        }
    }
}

@Composable
private fun LauncherStatusCard(
    vmState: VmState,
    bootStage: String,
) {
    val (label, color) = when (vmState) {
        is VmState.Running -> stringResource(R.string.status_running) to PodroidStatusColors.Running
        is VmState.Starting -> stringResource(R.string.status_starting) to PodroidStatusColors.Starting
        is VmState.Stopped -> stringResource(R.string.status_stopped) to PodroidStatusColors.Stopped
        is VmState.Error -> stringResource(R.string.error_title) to PodroidStatusColors.Error
        is VmState.Idle -> stringResource(R.string.status_idle) to PodroidStatusColors.Stopped
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(PodroidTokens.Spacing.XL),
            verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.MD),
        ) {
            Text(
                text = stringResource(R.string.launcher_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(R.string.launcher_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PodroidStatus(label = label, dotColor = color)
            if (bootStage.isNotBlank() && vmState is VmState.Starting) {
                Text(
                    text = bootStage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (vmState is VmState.Error && vmState.message.isNotBlank()) {
                Text(
                    text = vmState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun LauncherActions(
    vmState: VmState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    val isStarting = vmState is VmState.Starting
    val isRunning = vmState is VmState.Running

    Column(
        verticalArrangement = Arrangement.spacedBy(PodroidTokens.Spacing.SM),
    ) {
        if (!isRunning && !isStarting) {
            PodroidPrimaryButton(
                text = stringResource(R.string.start_vm),
                onClick = onStart,
                icon = Icons.Default.PlayArrow,
            )
        }
        if (isRunning) {
            PodroidGhostButton(
                text = stringResource(R.string.restart),
                onClick = onRestart,
                icon = Icons.Default.Refresh,
            )
            PodroidDestructiveButton(
                text = stringResource(R.string.stop),
                onClick = onStop,
                icon = Icons.Default.Stop,
            )
        }
        if (isStarting) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(PodroidTokens.Spacing.LG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PodroidStatus(
                        label = stringResource(R.string.launcher_starting_hint),
                        dotColor = PodroidStatusColors.Starting,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.launcher_starting_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(1.dp))
    }
}
