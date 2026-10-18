package com.snowyghost.ovenlight

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Send feedback while it's open: what the screen showed ([screenshot], in the screen's pixels)
 * and where, with the note, the screenshot choice, a send under way and its outcome. The app's
 * page holds it ([AppPage.feedback]), so the lock, which takes the screen apart, doesn't lose it.
 */
@Stable
class FeedbackDraft(val screenshot: Bitmap?, val pageURL: URI?) {
    val note = TextFieldState()
    var includeScreenshot by mutableStateOf(true)
    var sending by mutableStateOf(false)
    var result by mutableStateOf<FeedbackResult?>(null)
}

/**
 * Send feedback from the app menu, as a full-screen dialog: a note and, unless left out, what
 * the screen showed, [density] of its pixels to a dp. [send] posts the payload (FeedbackSender
 * in the app) in [scope], which outlives the dialog so a lock mid-send still records how it went;
 * once it's sent the dialog says so and closes after 1.4 s, and a failure shows under the note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackSheet(
    app: WebApp,
    draft: FeedbackDraft,
    density: Float,
    send: suspend (FeedbackPayload) -> FeedbackResult,
    onDismiss: () -> Unit,
    scope: CoroutineScope,
) {
    // The lock can take the sheet away with the keyboard up, and the keyboard's stale back
    // callback would then swallow the first Back after the unlock.
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    val note = draft.note
    val screenshot = draft.screenshot
    val includeScreenshot = draft.includeScreenshot
    val result = draft.result
    val sending = draft.sending
    val haptics = LocalHapticFeedback.current
    val tooLong = stringResource(R.string.feedback_note_too_long)
    val canSend = !sending && result != FeedbackResult.Sent &&
        (note.text.isNotBlank() || (includeScreenshot && screenshot != null))

    fun submit() = scope.launch {
        // Taken at once, so a second tap while the PNG is made sends nothing more.
        draft.sending = true
        val png = if (includeScreenshot && screenshot != null) withContext(Dispatchers.Default) { FeedbackPayload.png(screenshot, density) } else null
        val payload = try {
            FeedbackPayload.make(note.text.toString(), draft.pageURL, png)
        } catch (problem: FeedbackPayload.Problem) {
            draft.sending = false
            draft.result = FeedbackResult.Failed(if (problem == FeedbackPayload.Problem.NoteTooLong) tooLong else ConnectionCopy.feedbackFailed)
            haptics.performHapticFeedback(HapticFeedbackType.Reject)
            return@launch
        }
        draft.result = null
        val outcome = send(payload)
        draft.sending = false
        draft.result = outcome
        haptics.performHapticFeedback(if (outcome == FeedbackResult.Sent) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
    }
    // Here rather than after the send, so a sheet the lock took away while it said so closes too.
    LaunchedEffect(result) {
        if (result == FeedbackResult.Sent) {
            delay(1_400)
            onDismiss()
        }
    }

    // A note would be lost: closing asks first.
    var askingToDiscard by remember { mutableStateOf(false) }
    val close = { if (note.text.isNotBlank() && result != FeedbackResult.Sent) askingToDiscard = true else onDismiss() }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = !sending),
    ) {
        FullScreenDialogBars()
        val title = stringResource(R.string.send_feedback)
        val background = LocalOvenColors.current.groupedBackground
        val bar = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(bar.nestedScrollConnection).semantics { paneTitle = title },
            containerColor = background,
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = close) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        if (sending) {
                            val label = stringResource(R.string.feedback_sending)
                            Box(Modifier.size(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            }
                        } else {
                            TextButton(onClick = { submit() }, enabled = canSend) { Text(stringResource(R.string.feedback_send)) }
                        }
                    },
                    colors = scrollingBarColors(background),
                    scrollBehavior = bar,
                )
            },
        ) { bars ->
            Box(Modifier.fillMaxSize().padding(bars)) {
                FeedbackForm(app, screenshot, note, includeScreenshot, { draft.includeScreenshot = it }, result)
                AnimatedVisibility(result == FeedbackResult.Sent, enter = fadeIn(), exit = fadeOut()) { FeedbackSent(app) }
            }
        }
        if (askingToDiscard) {
            Confirmation(
                stringResource(R.string.feedback_discard_title), message = null, confirm = stringResource(R.string.feedback_discard),
                onDismiss = { askingToDiscard = false }, cancel = stringResource(R.string.feedback_keep_editing), action = onDismiss,
            )
        }
    }
}

@Composable
private fun FeedbackForm(
    app: WebApp,
    screenshot: Bitmap?,
    note: TextFieldState,
    includeScreenshot: Boolean,
    setIncludeScreenshot: (Boolean) -> Unit,
    result: FeedbackResult?,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    // With nothing to look at, the note is the whole form: the keyboard comes up for it.
    LaunchedEffect(Unit) { if (screenshot == null) focus.requestFocus() }
    Column(
        // The keyboard shrinks the scrolling area, so the field being typed in scrolls into view above it.
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val failure = (result as? FeedbackResult.Failed)?.message
        OutlinedTextField(
            note,
            label = { Text(stringResource(R.string.feedback_note)) },
            lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 4, maxHeightInLines = 10),
            isError = failure != null,
            supportingText = {
                // A failure takes the footer's place, where it shows above the keyboard.
                if (failure != null) {
                    FieldWarning(failure)
                } else {
                    Text(
                        app.sharedBy?.let { stringResource(R.string.feedback_goes_to, it, app.name) } ?: stringResource(R.string.feedback_goes_to_you),
                    )
                }
            },
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().focusRequester(focus),
        )
        if (screenshot != null) {
            Surface(color = LocalOvenColors.current.groupedRow, shape = RoundedCornerShape(28.dp), modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.feedback_include_screenshot)) },
                        trailingContent = { Switch(checked = includeScreenshot, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = LocalOvenColors.current.groupedRow),
                        modifier = Modifier.toggleable(includeScreenshot, role = Role.Switch, onValueChange = setIncludeScreenshot),
                    )
                    AnimatedVisibility(includeScreenshot) {
                        val image = remember(screenshot) { screenshot.asImageBitmap() }
                        val shape = RoundedCornerShape(12.dp)
                        Image(
                            image, contentDescription = stringResource(R.string.feedback_screenshot, app.name), contentScale = ContentScale.Fit,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).heightIn(max = 260.dp).clip(shape)
                                .border(0.5.dp, colors.onSurface.copy(alpha = 0.1f), shape),
                        )
                    }
                }
            }
        }
    }
}

/** Sent: a check, "Feedback sent" and who will see it, over the form until the dialog closes. */
@Composable
private fun FeedbackSent(app: WebApp) {
    Column(
        Modifier.fillMaxSize().background(LocalOvenColors.current.groupedBackground).padding(32.dp).semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = LocalOvenColors.current.success, modifier = Modifier.size(48.dp))
        Text(ConnectionCopy.feedbackSent, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            ConnectionCopy.feedbackSentDetail(to = app.sharedBy), Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}
