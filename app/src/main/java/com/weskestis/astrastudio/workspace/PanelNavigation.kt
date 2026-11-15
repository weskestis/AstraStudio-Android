package com.weskestis.astrastudio.workspace

import androidx.compose.runtime.*

/** Dialog-window Back and the visible Back control share the same child stack. */
class PanelNavigation {
    private val handlers=mutableListOf<Pair<Any,()->Boolean>>()
    fun add(token: Any,handler: ()->Boolean){handlers.add(token to handler)}
    fun remove(token: Any){handlers.removeAll{it.first===token}}
    fun back(): Boolean=handlers.asReversed().any{it.second()}
}
internal val LocalPanelNavigation=staticCompositionLocalOf<PanelNavigation?>{null}
@Composable
fun PanelBackHandler(enabled: Boolean,onBack: ()->Unit) {
    val navigation=LocalPanelNavigation.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentBack by rememberUpdatedState(onBack)
    DisposableEffect(navigation){val token=Any();navigation?.add(token){if(currentEnabled){currentBack();true}else false};onDispose{navigation?.remove(token)}}
}
