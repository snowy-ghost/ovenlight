package com.snowyghost.ovenlight

/**
 * Shown over an app when its connection needs someone: the owner's sign-in or device approval,
 * or a failure with a retry. Plain waiting is the app's launch placeholder. A guest never sees
 * sign-in, approval or Tailscale. The overlay itself comes with the app host.
 */
object NodeActionOverlay {
    sealed class Action {
        data object SignIn : Action()
        data object OwnerApproval : Action()
        data class Failed(val message: String) : Action()
    }

    fun action(state: NodeState, app: WebApp, ownerEnabled: Boolean): Action? {
        if (app.isShared) {
            // A lost login means the share ended; Ovenlight removes the app.
            return if (state is NodeState.Failed) Action.Failed(state.message) else null
        }
        return when {
            state is NodeState.NeedsLogin -> Action.SignIn
            state == NodeState.Idle && !ownerEnabled -> Action.SignIn
            state == NodeState.AwaitingApproval -> Action.OwnerApproval
            state is NodeState.Failed -> Action.Failed(state.message)
            else -> null
        }
    }
}
