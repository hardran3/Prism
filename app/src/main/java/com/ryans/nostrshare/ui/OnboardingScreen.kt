package com.ryans.nostrshare.ui

import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Schedule
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

@Composable
fun OnboardingScreen(
    vm: ProcessTextViewModel,
    getPublicKeyLauncher: ActivityResultLauncher<GetPublicKeyContract.Input>
) {
    val context = LocalContext.current
    val step = vm.currentOnboardingStep
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasAlarmPermission by remember { mutableStateOf(false) }
    var hasBatteryPermission by remember { mutableStateOf(false) }
    var hasNotificationPermission by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // Function to perform checks
    fun checkPermissions() {
        if (step == OnboardingStep.SCHEDULING_CONFIG) {
            // Check Alarms
            hasAlarmPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            } else true

            // Check Battery
            val pm = context.getSystemService(PowerManager::class.java)
            hasBatteryPermission = pm.isIgnoringBatteryOptimizations(context.packageName)

            // Check Notifications
            hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true

            // Auto-advance if ALL are granted
            if (hasAlarmPermission && hasBatteryPermission && hasNotificationPermission) {
                vm.completeSchedulingConfig()
            }
        }
    }

    // Auto-advance when step changes or via polling
    LaunchedEffect(step) {
        while (true) {
            checkPermissions()
            kotlinx.coroutines.delay(1000) // Poll every 1s
        }
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
        if (step == OnboardingStep.SCHEDULING_CONFIG) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
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
            OnboardingStep.SCHEDULING_CONFIG -> {
                Text(
                    text = "Scheduling Setup",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "For the most reliable scheduled posting, especially on mobile data, please enable the following settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(32.dp))
                
                PermissionRow(
                    title = "Precise Alarms",
                    description = "Ensures notes trigger exactly on time.",
                    isGranted = hasAlarmPermission,
                    icon = Icons.Default.AccessTime,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    }
                )
                
                PermissionRow(
                    title = "Battery Unrestricted",
                    description = "Prevents system from killing background tasks.",
                    isGranted = hasBatteryPermission,
                    icon = Icons.Default.BatteryChargingFull,
                    onClick = {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    }
                )
                
                PermissionRow(
                    title = "Notifications",
                    description = "Required for foreground execution on Android 13+.",
                    isGranted = hasNotificationPermission,
                    icon = Icons.Default.Notifications,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
                
                Spacer(modifier = Modifier.height(48.dp))
                
                TextButton(
                    onClick = { vm.skipSchedulingConfig() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip scheduling setup")
                }
            }
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (isGranted) Color(0xFF4CAF50).copy(alpha = 0.1f) 
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (isGranted) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary
            )
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        if (isGranted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Ready",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(24.dp)
            )
        } else {
            Button(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Enable", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}
