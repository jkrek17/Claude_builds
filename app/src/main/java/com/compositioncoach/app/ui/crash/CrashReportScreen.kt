package com.compositioncoach.app.ui.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shown once, before anything else, when the previous run crashed. Lets a tester copy or share the trace
 * and then continue into the app. Deliberately plain: it must render even when the rest of the app is
 * broken, so it uses nothing but core Compose and platform services.
 */
@Composable
fun CrashReportScreen(report: String, onContinue: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .safeDrawingPadding()
            .padding(20.dp),
    ) {
        Text("The app crashed last time", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            "Copy this report and send it to the developer. Then tap Continue.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { copyToClipboard(context, report) }, modifier = Modifier.weight(1f)) { Text("Copy") }
            OutlinedButton(onClick = { share(context, report) }, modifier = Modifier.weight(1f)) { Text("Share") }
            OutlinedButton(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Continue") }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = report,
            color = Color(0xFFE0E0E0),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E1E1E))
                .padding(10.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Composition Coach crash report", text))
}

private fun share(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Composition Coach crash report")
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, "Share crash report")) }
}
