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
    onSwitchAccount: (String) -> Unit,
    addAccountAtTop: Boolean = false
) {
    if (addAccountAtTop) {
        if (expanded) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, 0),
                onDismissRequest = onDismiss,
                properties = PopupProperties(focusable = true)
            ) {
                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    tonalElevation = 4.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.width(IntrinsicSize.Max)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        AddAccountItem(onAddAccount)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        vm.knownAccounts.forEach { account ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        UserAvatar(
                                            pictureUrl = account.pictureUrl,
                                            pubkey = account.pubkey,
                                            vm = vm,
                                            size = 32.dp
                                        )
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
                    }
                }
            }
        }
    } else {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = (-8).dp),
            properties = PopupProperties(focusable = false),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            vm.knownAccounts.forEach { account ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(
                                pictureUrl = account.pictureUrl,
                                pubkey = account.pubkey,
                                vm = vm,
                                size = 32.dp
                            )
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
            AddAccountItem(onAddAccount)
        }
    }
}

@Composable
private fun AddAccountItem(onAddAccount: () -> Unit) {
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
