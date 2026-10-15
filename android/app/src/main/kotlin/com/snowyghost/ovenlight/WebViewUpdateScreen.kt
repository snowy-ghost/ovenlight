package com.snowyghost.ovenlight

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Shown instead of any app when this phone's WebView can't run Ovenlight's apps. */
@Composable
fun WebViewUpdateScreen(webViewPackage: String) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.webview_update_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(stringResource(R.string.webview_update_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = {
                val market = Intent(Intent.ACTION_VIEW, "market://details?id=$webViewPackage".toUri())
                try {
                    context.startActivity(market)
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$webViewPackage".toUri()))
                }
            }) { Text(stringResource(R.string.webview_update_button)) }
        }
    }
}
