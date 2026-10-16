package com.snowyghost.ovenlight

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
        CenteredScroll(contentPadding = 32.dp, spacing = 16.dp) {
            Text(stringResource(R.string.webview_update_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(stringResource(R.string.webview_update_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Button(onClick = {
                // No store and no browser: nothing opens.
                context.startOrFalse(Intent(Intent.ACTION_VIEW, "market://details?id=$webViewPackage".toUri())) ||
                    context.startOrFalse(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$webViewPackage".toUri()))
            }) { Text(stringResource(R.string.webview_update_button)) }
        }
    }
}
