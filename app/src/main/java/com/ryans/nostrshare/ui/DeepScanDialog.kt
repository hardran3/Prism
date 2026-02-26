package com.ryans.nostrshare.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.utils.DeepScanManager
import com.ryans.nostrshare.utils.DeepScanManager.OverallPhase

@Composable
fun DeepScanDialog(
    onDismiss: () -> Unit,
    onStartScan: (Boolean) -> Unit, // Boolean for isDeep
) {
    val progressState by DeepScanManager.progressState.collectAsState()
    val phase = progressState.phase

    AlertDialog(
        onDismissRequest = { if (phase == OverallPhase.IDLE || phase == OverallPhase.COMPLETED) onDismiss() },
        title = {
            Text(
                text = when (phase) {
                    OverallPhase.IDLE -> "Sync Full History"
                    OverallPhase.COMPLETED -> "Scan Completed"
                    OverallPhase.ERROR -> "Scan Error"
                    else -> "Syncing History..."
                },
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (phase) {
                    OverallPhase.IDLE -> {
                        Text(
                            "Choose how to retrieve your past notes.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        OutlinedButton(
                            onClick = { onStartScan(false) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Standard (Outbox)")
                                Text("Fastest | Queries your own relays", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Button(
                            onClick = { onStartScan(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Deep Scan")
                                Text("Thorough | Finds notes via followers", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    OverallPhase.COMPLETED -> {
                        Text("Found and saved ${progressState.newNotesFound} new notes.", textAlign = TextAlign.Center)
                    }
                    OverallPhase.ERROR -> {
                        Text(progressState.error ?: "An unknown error occurred.", color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    else -> {
                        PhaseProgressBar("1. Harvesting Relays", progressState.relayDiscoveryProgress)
                        Spacer(Modifier.height(12.dp))
                        PhaseProgressBar("2. Health Check", progressState.healthCheckProgress)
                        Spacer(Modifier.height(12.dp))
                        PhaseProgressBar("3. Syncing Notes", progressState.syncProgress)
                        Spacer(Modifier.height(20.dp))
                        
                        AnimatedVisibility(visible = progressState.newNotesFound > 0) {
                            Text(
                                text = "${progressState.newNotesFound} new notes found",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        }
                        
                        Text(
                            text = progressState.currentActivity ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (phase == OverallPhase.IDLE) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            } else if (phase == OverallPhase.COMPLETED || phase == OverallPhase.ERROR) {
                Button(onClick = onDismiss) { Text("Close") }
            } else {
                TextButton(
                    onClick = { DeepScanManager.stopScan() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Stop")
                }
            }
        }
    )
}

@Composable
private fun PhaseProgressBar(title: String, progress: Float) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
    }
}
