package com.mezo.trace.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.mezo.trace.ui.components.ErrorBox
import kotlinx.coroutines.launch
import com.mezo.trace.ui.components.PrimaryButton
import com.mezo.trace.ui.theme.TraceDimens

/**
 * First-use experience (plan §3.1). Sign-in and registration share one form;
 * registration additionally provisions the user's personal workspace.
 */
@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    signIn: suspend (String, String) -> Unit,
    register: suspend (String, String, String?) -> Unit,
) {
    var registering by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val fieldShape = RoundedCornerShape(TraceDimens.FieldRadius + 2.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surface,
        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Trace",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "What do you want to keep an eye on?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (!registering) {
                "Sign in to your intelligence."
            } else {
                "Create your account to start monitoring."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            shape = fieldShape,
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = fieldShape,
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = if (registering) ImeAction.Next else ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )
        if (registering) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let {
            Spacer(Modifier.height(10.dp))
            ErrorBox(it, Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(22.dp))
        PrimaryButton(
            text = if (busy) {
                if (!registering) "Signing in…" else "Creating…"
            } else if (!registering) "Sign in" else "Create account",
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        if (registering) {
                            register(email.trim(), password, displayName.trim().ifEmpty { null })
                        } else {
                            signIn(email.trim(), password)
                        }
                        onAuthenticated()
                    } catch (e: Exception) {
                        error = e.message ?: "something went wrong"
                    } finally {
                        busy = false
                    }
                }
            },
        )

        TextButton(onClick = { registering = !registering; error = null }) {
            Text(
                if (!registering) "New here? Create an account" else "Already have an account? Sign in",
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
