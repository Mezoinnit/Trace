package com.mezo.trace.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mezo.trace.ui.components.ErrorBox
import kotlinx.coroutines.launch

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

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("What do you want to keep an eye on?", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (!registering) "Sign in to your intelligence." else "Create your account to start monitoring.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (registering) {
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text("Display name (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        error?.let { ErrorBox(it, Modifier.fillMaxWidth()) }

        Button(
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
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (!registering) "Sign in" else "Create account")
        }

        OutlinedButton(
            onClick = {
                registering = !registering
                error = null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (!registering) "New here? Create an account" else "Already have an account? Sign in")
        }
    }
}
