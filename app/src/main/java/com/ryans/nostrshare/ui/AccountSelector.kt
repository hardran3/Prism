package com.ryans.nostrshare.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.ryans.nostrshare.ProcessTextViewModel

@Composable
fun AccountSelectorMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    vm: ProcessTextViewModel,
    onAddAccount: () -> Unit,
    onSwitchAccount: (String) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = androidx.compose.ui.unit.DpOffset(x = 8.dp, y = 0.dp),
        properties = PopupProperties(focusable = false)
    ) {
        vm.knownAccounts.forEach { account ->
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (account.pictureUrl != null) {
                            AsyncImage(
                                model = account.pictureUrl,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Default.Person, null, modifier = Modifier.size(32.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = account.name ?: (account.npub?.take(12) ?: account.pubkey.take(8)),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (vm.pubkey == account.pubkey) FontWeight.Bold else FontWeight.Normal,
                            color = if (vm.pubkey == account.pubkey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                onClick = { onSwitchAccount(account.pubkey) }
            )
        }
        
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        
        DropdownMenuItem(
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PersonAdd, null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Add Account", style = MaterialTheme.typography.bodyLarge)
                }
            },
            onClick = onAddAccount
        )
    }
}
