package com.snowyghost.ovenlight

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Shown instead of any app when this phone's WebView can't run Ovenlight's apps. [contactUs] makes the Contact us draft. */
@Composable
fun WebViewUpdateScreen(update: WebViewSetup.State.NeedsUpdate, contactUs: () -> MailDraft) {
    val context = LocalContext.current
    var noEmailApp by remember { mutableStateOf<MailDraft?>(null) }
    Surface(Modifier.fillMaxSize()) {
        CenteredScroll(contentPadding = 32.dp, spacing = 16.dp) {
            Text(stringResource(R.string.webview_update_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(stringResource(R.string.webview_update_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            update.version?.let {
                Text(stringResource(R.string.webview_update_version, it, WebViewSetup.MINIMUM_MAJOR_VERSION), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
            Button(onClick = {
                // No store and no browser: nothing opens.
                context.startOrFalse(Intent(Intent.ACTION_VIEW, "market://details?id=${update.webViewPackage}".toUri())) ||
                    context.startOrFalse(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${update.webViewPackage}".toUri()))
            }) { Text(stringResource(R.string.webview_update_button)) }
            TextButton(onClick = { noEmailApp = contactUs().takeUnless { it.send(context) } }) { Text(stringResource(R.string.contact_us)) }
        }
    }
    noEmailApp?.let { NoEmailAppDialog(it, onDismiss = { noEmailApp = null }) }
}
