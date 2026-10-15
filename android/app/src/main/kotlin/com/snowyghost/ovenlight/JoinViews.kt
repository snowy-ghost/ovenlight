package com.snowyghost.ovenlight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.net.URI
import java.util.UUID

/** An invite to show in the join sheet: read from a link, or null for the Join with Invite entry. */
class JoinRequest(val invite: Result<InviteLink>? = null) {
    val id: UUID = UUID.randomUUID()

    /** The link's invite, or why it isn't one ([InviteLink.ParseError]). */
    constructor(url: URI) : this(
        try {
            Result.success(InviteLink.parse(url))
        } catch (e: InviteLink.ParseError) {
            Result.failure(e)
        },
    )
}

/** The launcher's first screen, before any app: anyone may see it, so it names no network. */
object LauncherWelcome {
    const val title = "Welcome to Ovenlight"
    const val message = "Open the invite a friend sent you, or connect the computers where your own apps run."
}

/**
 * The launcher before anything is set up. Nothing here assumes the person runs their own
 * computers: a friend who got an invite starts here too, and never sees a sign-in. Scrolls only
 * when the largest font sizes don't fit.
 */
@Composable
fun LauncherWelcome(join: () -> Unit, useOwnComputers: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val glow = LocalOvenColors.current.lampGlow.copy(alpha = if (isSystemInDarkTheme()) 0.25f else 0.2f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The empty rack, in the light, waiting for apps.
            Box(Modifier.size(270.dp, 64.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize().clearAndSetSemantics {}) {
                    scale(scaleX = 1f, scaleY = size.height / size.width) {
                        drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center = center, radius = size.width / 2), radius = size.width / 2)
                    }
                }
                OvenRack(250.dp)
            }
            Spacer(Modifier.height(30.dp))
            Text(LauncherWelcome.title, style = OvenType.displayTitle, color = colors.onSurface, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(LauncherWelcome.message, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = join, modifier = Modifier.height(56.dp)) {
                Text(ConnectionCopy.joinWithInvite, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = useOwnComputers) { Text(stringResource(R.string.launcher_use_own_computers)) }
        }
    }
}

/** Above the apps: invites still joining, or stopped by a failure. Tapping one reopens it in the join screen. */
@Composable
fun PendingInvitesBanner(invites: List<InviteLink>, phases: Map<String, JoinPhase>, open: (InviteLink) -> Unit, modifier: Modifier = Modifier) {
    if (invites.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val container = if (isSystemInDarkTheme()) colors.surfaceContainerHigh else colors.surfaceContainerLowest
    Column(modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (invite in invites.distinctBy { it.invite }) {
            key(invite.invite) {
                val failed = (phases[invite.invite] as? JoinPhase.Failed)?.error
                val app = remember(invite.invite) { WebApp(name = invite.name, startURL = invite.appURL) }
                Surface(onClick = { open(invite) }, shape = RoundedCornerShape(16.dp), color = container, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIconView(app, image = null, size = 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(invite.name, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                            Text(
                                failed?.title(invite.owner, invite.name) ?: ConnectionCopy.joining(invite.owner, invite.name),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            )
                        }
                        if (failed != null) {
                            Icon(painterResource(R.drawable.ic_error), contentDescription = null, tint = colors.tertiary)
                        } else {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }
        }
    }
}
