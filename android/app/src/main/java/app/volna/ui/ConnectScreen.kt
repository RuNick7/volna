package app.volna.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.volna.VolnaApp
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Вход: адрес Navidrome, логин и пароль от него. Сервис Volna приложение находит само,
 * он живёт рядом с Navidrome по пути /volna.
 */
@Composable
fun ConnectScreen() {
    val app = LocalContext.current.applicationContext as VolnaApp
    val prefs = app.prefs
    var address by remember { mutableStateOf(prefs.address) }
    var user by remember { mutableStateOf(prefs.user) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(if (prefs.connected) "Войдите снова" else null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy || address.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                app.api.connect(address, user, password)
            } catch (e: IOException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Topo(Modifier.fillMaxSize(), mode = UiPrefs.background)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        ) {
            Text("vaginawave", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 6.sp), modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(96.dp))
            Text("Вход", style = MaterialTheme.typography.headlineLarge)
            Hint("Адрес вашего Navidrome, логин и пароль от него.")
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                label = { Text("Сервер") },
                placeholder = { Text("music.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                singleLine = true,
                label = { Text("Логин") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                label = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = textFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
            }
            Spacer(Modifier.height(20.dp))
            Pill(if (busy) "Подключаюсь…" else "Войти", primary = true) { submit() }
            Spacer(Modifier.height(16.dp))
            Hint("Свой компьютер в домашней сети: введите его IP, например 192.168.1.20:8765.")
            Spacer(Modifier.height(32.dp))
        }
    }
}
