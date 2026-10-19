package com.steve.mcpodface.ui.addpodcast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.steve.mcpodface.ui.theme.AppButtonDefaults
import com.steve.mcpodface.ui.theme.AppTheme
import com.steve.mcpodface.ui.theme.Spacing
import com.steve.mcpodface.ui.theme.ThemePreviews
import java.util.Locale

private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
private val PODCAST_SCHEMES = setOf("feed", "pcast", "itpc")

/**
 * Turns what the user typed into a feed URL: trims it, prepends https:// when there is no scheme,
 * and maps podcast-app schemes (feed://, pcast://, itpc://) to https. Null when blank.
 */
internal fun normalizeFeedUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val match = SCHEME_PREFIX.find(trimmed) ?: return "https://$trimmed"
    val scheme = trimmed.substring(0, match.value.length - 3).lowercase(Locale.ROOT)
    if (scheme !in PODCAST_SCHEMES) return trimmed
    val rest = trimmed.substring(match.value.length).trim()
    if (rest.isEmpty()) return null
    val isWeb = rest.startsWith("http://", ignoreCase = true) || rest.startsWith("https://", ignoreCase = true)
    return if (isWeb) rest else "https://$rest"
}

/**
 * "Add by URL" (PLAN.md §3.3). Keeps the typed fields across rotation; the password is not saved.
 * [working] disables the form while the feed is fetched, [error] is shown inline and keeps it open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddByUrlDialog(
    working: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onAdd: (url: String, authUser: String?, authPassword: String?) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var isPrivate by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val normalized = normalizeFeedUrl(url)

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Add by URL") },
        text = {
            AddByUrlForm(
                url = url,
                onUrlChange = { url = it },
                isPrivate = isPrivate,
                onPrivateChange = { isPrivate = it },
                username = username,
                onUsernameChange = { username = it },
                password = password,
                onPasswordChange = { password = it },
                passwordVisible = passwordVisible,
                onPasswordVisibleChange = { passwordVisible = it },
                enabled = !working,
                error = error,
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (normalized != null) {
                        val user = username.trim().takeIf { isPrivate && it.isNotEmpty() }
                        val pass = password.takeIf { isPrivate && user != null && it.isNotEmpty() }
                        onAdd(normalized, user, pass)
                    }
                },
                enabled = !working && normalized != null,
                colors = AppButtonDefaults.primaryColors(),
            ) {
                if (working) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.onButtonPrimary,
                    )
                    Spacer(Modifier.width(Spacing.s))
                }
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") }
        },
    )
}

/** The dialog body: the URL field, the "Private feed" toggle with credentials, and the error. */
@Composable
internal fun AddByUrlForm(
    url: String,
    onUrlChange: (String) -> Unit,
    isPrivate: Boolean,
    onPrivateChange: (Boolean) -> Unit,
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    passwordVisible: Boolean,
    onPasswordVisibleChange: (Boolean) -> Unit,
    enabled: Boolean,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text("Feed URL") },
            placeholder = { Text("https://example.com/feed.xml") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onPrivateChange(!isPrivate) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = isPrivate, onCheckedChange = null, enabled = enabled)
            Text("Private feed", style = MaterialTheme.typography.bodyLarge)
        }
        if (isPrivate) {
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    IconButton(onClick = { onPasswordVisibleChange(!passwordVisible) }, enabled = enabled) {
                        Icon(
                            if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
            )
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@ThemePreviews
@Composable
private fun AddByUrlFormEmptyPreview() {
    AppTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                AddByUrlForm(
                    url = "", onUrlChange = {},
                    isPrivate = false, onPrivateChange = {},
                    username = "", onUsernameChange = {},
                    password = "", onPasswordChange = {},
                    passwordVisible = false, onPasswordVisibleChange = {},
                    enabled = true, error = null,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AddByUrlFormPrivateFeedPreview() {
    AppTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                AddByUrlForm(
                    url = "https://patreon.example.com/feed/abc", onUrlChange = {},
                    isPrivate = true, onPrivateChange = {},
                    username = "steve", onUsernameChange = {},
                    password = "secret", onPasswordChange = {},
                    passwordVisible = false, onPasswordVisibleChange = {},
                    enabled = true, error = null,
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun AddByUrlFormErrorPreview() {
    AppTheme {
        Surface {
            Column(Modifier.fillMaxWidth().padding(Spacing.m)) {
                AddByUrlForm(
                    url = "example.com/feed.xml", onUrlChange = {},
                    isPrivate = false, onPrivateChange = {},
                    username = "", onUsernameChange = {},
                    password = "", onPasswordChange = {},
                    passwordVisible = false, onPasswordVisibleChange = {},
                    enabled = true,
                    error = "401 Unauthorized: check the username and password",
                )
            }
        }
    }
}
