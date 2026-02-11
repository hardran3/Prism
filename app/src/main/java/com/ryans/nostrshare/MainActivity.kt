package com.ryans.nostrshare

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.material3.* // Keep generic if needed, or remove if unused. But let's just clean specific ones.
// Actually, MainActivity doesn't use Compose since we removed setContent.
// It just uses Intent and startActivity.

import android.content.Intent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Redirect to ProcessTextActivity
        val intent = Intent(this, ProcessTextActivity::class.java)
        intent.putExtra("LAUNCH_MODE", "NOTE") // Explicitly marking as note mode
        startActivity(intent)
        finish()
    }
}
