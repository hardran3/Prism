package com.ryans.nostrshare.nip55

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object Nip55 {

    fun isSignerAvailable(context: Context): Boolean {
        return getAvailableSigners(context).isNotEmpty()
    }

    fun getAvailableSigners(context: Context): List<SignerInfo> {
        val intent = Nip55Protocol.createDiscoveryIntent()
        val packageManager = context.packageManager

        val resolveInfos = packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )

        return resolveInfos.map { info ->
            info.toSignerInfo(
                appName = info.loadLabel(packageManager).toString()
            )
        }
    }

    fun createIntentForSigner(packageName: String): Intent {
        return Nip55Protocol.createIntent().apply {
            setPackage(packageName)
        }
    }
}
