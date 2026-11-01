package app.volna.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.volna.BuildConfig
import app.volna.Release
import app.volna.UpdateStep
import app.volna.Updates

/** «Доступна VaginaWave X»: что нового, скачать и установить поверх. */
@Composable
fun UpdateSheet(onDismiss: () -> Unit) {
    val release by Updates.available.collectAsState()
    val step by Updates.step.collectAsState()
    val r = release ?: return
    Sheet(onDismiss) {
        Text("Доступна VaginaWave ${r.version}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
        Hint("У вас ${BuildConfig.VERSION_NAME}")
        ReleaseNotes(r)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            UpdateButton(step)
            if (step !is UpdateStep.Downloading) Pill("Позже", onClick = onDismiss)
        }
        UpdateProgress(step)
    }
}

@Composable
fun ReleaseNotes(release: Release) {
    if (release.notes.isEmpty()) return
    Text(
        release.notes,
        style = MaterialTheme.typography.bodyMedium,
        color = Palette.Muted,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** Главная кнопка по шагу: скачать, установить или ничего, пока качается. */
@Composable
fun UpdateButton(step: UpdateStep) {
    when (step) {
        is UpdateStep.Ready -> Pill("Установить", VIcons.Download, primary = true) { Updates.install(step.file) }
        is UpdateStep.Downloading -> {}
        else -> Pill("Скачать и установить", VIcons.Download, primary = true) { Updates.download() }
    }
}

@Composable
fun UpdateProgress(step: UpdateStep) {
    when (step) {
        is UpdateStep.Downloading -> Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            LinearProgressIndicator(
                progress = { step.progress },
                color = Palette.Fg,
                trackColor = Palette.Faint,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Hint("Скачиваю… ${(step.progress * 100).toInt()} %")
        }
        is UpdateStep.Ready -> Hint(
            "Если Android спросит — разрешите VaginaWave устанавливать приложения. Вход, загрузки и настройки сохранятся.",
            Modifier.padding(top = 8.dp),
        )
        is UpdateStep.Failed -> Hint(step.message, Modifier.padding(top = 8.dp))
        UpdateStep.Idle -> {}
    }
}
