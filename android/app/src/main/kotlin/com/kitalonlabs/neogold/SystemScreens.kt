package com.kitalonlabs.neogold

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kitalonlabs.neogold.core.security.integrity.IntegrityResult

/**
 * Screens that stand in for the app when something underneath it is not trustworthy: the saved data cannot be
 * opened, or the app's own files failed their check. They show no clinical content and no result.
 */

@Composable
fun StorageOpeningScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.storage_opening), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Saved data cannot be opened (doc 8 Section 5.1, TC-SEC-07). The user chooses; nothing is deleted before that.
 */
@Composable
fun StorageUnreadableScreen(onTryAgain: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.storage_reset_confirm_title)) },
            text = { Text(text = stringResource(R.string.storage_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onReset()
                    },
                    modifier = Modifier.testTag("storage_reset_confirm")
                ) {
                    Text(text = stringResource(R.string.storage_reset_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.storage_reset_confirm_no))
                }
            }
        )
    }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.storage_unreadable_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("storage_unreadable_title")
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(R.string.storage_unreadable_body), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onTryAgain, modifier = Modifier.fillMaxWidth().testTag("storage_try_again")) {
            Text(text = stringResource(R.string.storage_unreadable_try_again))
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth().testTag("storage_reset")) {
            Text(text = stringResource(R.string.storage_unreadable_reset))
        }
    }
}

/**
 * The app's own files failed their check, or the build carries a development key (BUG-2026-007, BUG-2026-008).
 * Screening and research stay off; there is nothing on this screen that looks like a result.
 */
@Composable
fun IntegrityBlockedScreen(reason: IntegrityResult, modifier: Modifier = Modifier) {
    val body = if (reason is IntegrityResult.DevelopmentKeyInRelease) {
        R.string.integrity_blocked_dev_key_body
    } else {
        R.string.integrity_blocked_body
    }
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.integrity_blocked_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("integrity_blocked_title")
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = stringResource(body), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun DeletingAllDataScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.delete_all_deleting), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Some of the data could not be removed. The user can try again or close; nothing says it all went. */
@Composable
fun DeleteIncompleteScreen(
    incomplete: List<String>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.delete_all_incomplete_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.testTag("delete_incomplete_title")
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.delete_all_incomplete_body, incomplete.joinToString(", ")),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("delete_retry")) {
            Text(text = stringResource(R.string.delete_all_retry))
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().testTag("delete_close")) {
            Text(text = stringResource(R.string.delete_all_close))
        }
    }
}
