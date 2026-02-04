package com.ryans.nostrshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ryans.nostrshare.ui.theme.NostrShareTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Redirect to ProcessTextActivity
        val intent = android.content.Intent(this, ProcessTextActivity::class.java)
        intent.putExtra("LAUNCH_MODE", "NOTE") // Explicitly marking as note mode
        startActivity(intent)
        finish()
    }
}
