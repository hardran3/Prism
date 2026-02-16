package com.ryans.nostrshare.ui

import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.*
import com.ryans.nostrshare.R
import com.ryans.nostrshare.nip55.GetPublicKeyContract
import com.ryans.nostrshare.nip55.Nip55
import com.ryans.nostrshare.nip55.Permission
import android.os.PowerManager
import android.app.AlarmManager
import android.os.Build
import android.provider.Settings
import android.content.Intent
import android.net.Uri

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner

@Composable
fun OnboardingScreen(
    vm: ProcessTextViewModel,
    getPublicKeyLauncher: ActivityResultLauncher<GetPublicKeyContract.Input>
) {
    val context = LocalContext.current
    val step = vm.currentOnboardingStep
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Function to perform checks
    fun checkPermissions() {
        if (step == OnboardingStep.ALARM_PERMISSION) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                vm.currentOnboardingStep = OnboardingStep.BATTERY_OPTIMIZATION
            } else {
                val alarmManager = context.getSystemService(AlarmManager::class.java)
                if (alarmManager.canScheduleExactAlarms()) {
                    vm.currentOnboardingStep = OnboardingStep.BATTERY_OPTIMIZATION
                }
            }
        } else if (step == OnboardingStep.BATTERY_OPTIMIZATION) {
            val pm = context.getSystemService(PowerManager::class.java)
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                vm.completeOnboarding()
            }
        }
    }

    // Auto-advance when step changes
    LaunchedEffect(step) {
        checkPermissions()
    }
    
    // Auto-advance when returning from settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                checkPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val icon = when (step) {
            OnboardingStep.ALARM_PERMISSION -> Icons.Default.AccessTime
            OnboardingStep.BATTERY_OPTIMIZATION -> Icons.Default.BatteryChargingFull
            else -> null
        }

        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                painter = painterResource(id = R.drawable.ic_prism_triangle),
                contentDescription = null,
                modifier = Modifier.size(100.dp).clip(CircleShape),
                tint = Color.Unspecified
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        when (step) {
            OnboardingStep.WELCOME -> {
                Text(
                    text = "Welcome to Prism",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Prism makes it easy to share text, links, and media to the Nostr network. Connect your favorite NIP-55 signer up front to get started.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = {
                        if (Nip55.isSignerAvailable(context)) {
                            getPublicKeyLauncher.launch(
                                GetPublicKeyContract.Input(
                                    permissions = listOf(
                                        Permission.signEvent(9802),
                                        Permission.signEvent(1),
                                        Permission.signEvent(24242),
                                        Permission.signEvent(20),
                                        Permission.signEvent(22),
                                        Permission.signEvent(10063)
                                    ) 
                                )
                            )
                        } else {
                            Toast.makeText(context, "No NIP-55 Signer app found.", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Icon(Icons.Default.Person, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sign in with Amber / Signer")
                }
            }
            OnboardingStep.SYNCING -> {
                Text(
                    text = "Setting up your environment",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(24.dp))
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Finding your Blossom server list (Kind 10063) on your relays...",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OnboardingStep.SERVER_SELECTION -> {
                Text(
                    text = "Blossom Servers",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Blossom servers store your media across the Nostr network, keeping you in control of your content.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "We couldn't find a server list on your relays. We recommend picking between 1-3 servers.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Local selection for the defaults
                val repoDefaults = remember { vm.getFallBackServers() }
                var selectedServers by remember { 
                    mutableStateOf(
                        repoDefaults.filter { it.url.contains("primal.net") || it.url.contains("blossom.band") }
                            .map { it.url }.toSet()
                    ) 
                }
                
                Column(modifier = Modifier.fillMaxWidth()) {
                    repoDefaults.forEach { defaultServer ->
                        val isSelected = selectedServers.contains(defaultServer.url)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { 
                                    selectedServers = if (isSelected) {
                                        selectedServers - defaultServer.url
                                    } else {
                                        selectedServers + defaultServer.url
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { 
                                    selectedServers = if (isSelected) {
                                        selectedServers - defaultServer.url
                                    } else {
                                        selectedServers + defaultServer.url
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(defaultServer.url, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
                
                Button(
                    onClick = {
                        val serversToSave = repoDefaults.map { 
                            BlossomServer(it.url, selectedServers.contains(it.url))
                        }
                        vm.saveServersAndContinue(serversToSave)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = selectedServers.isNotEmpty()
                ) {
                    Text("Save & Continue")
                }
                
                TextButton(onClick = { 
                    vm.saveServersAndContinue(vm.blossomServers) 
                }) {
                    Text("Skip for now")
                }
            }
            OnboardingStep.ALARM_PERMISSION -> {
                Text(
                    text = "Scheduled Posting",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "To publish your notes at an exact time, Prism needs permission to set precise alarms. This ensures your content goes live even if the app is closed.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } else {
                            vm.currentOnboardingStep = OnboardingStep.BATTERY_OPTIMIZATION
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Grant Alarm Permission")
                }
                
                TextButton(onClick = { 
                    vm.currentOnboardingStep = OnboardingStep.BATTERY_OPTIMIZATION
                }) {
                    Text("Skip")
                }
            }
            OnboardingStep.BATTERY_OPTIMIZATION -> {
                Text(
                    text = "Reliable Background Tasks",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "Android's battery optimizations can sometimes delay or prevent background tasks. For the best experience, we recommend setting Prism to 'Unrestricted'.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                Button(
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("Remove Restrictions")
                }
                
                TextButton(onClick = { 
                    vm.completeOnboarding()
                }) {
                    Text("Finish Onboarding")
                }
            }
        }
    }
}
