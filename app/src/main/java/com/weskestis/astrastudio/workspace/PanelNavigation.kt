package com.weskestis.astrastudio.workspace

import androidx.compose.runtime.*

/** Dialog-window Back and the visible Back control share the same child stack. */
class PanelNavigation {
    private data class Handler(val token: Any,val depth: Int,val consume: ()->Boolean)
    private val handlers=mutableListOf<Handler>()
    fun add(token: Any,depth: Int=0,handler: ()->Boolean){handlers.add(Handler(token,depth,handler))}
    fun remove(token: Any){handlers.removeAll{it.token===token}}
    // Composition effects can register a parent after its child. Depth, rather
    // than effect registration order, determines which nested page goes back.
    fun back(): Boolean=handlers.asReversed().sortedByDescending{it.depth}.any{it.consume()}
}
internal val LocalPanelNavigation=staticCompositionLocalOf<PanelNavigation?>{null}
@Composable
fun PanelBackHandler(enabled: Boolean,depth: Int=0,onBack: ()->Unit) {
    val navigation=LocalPanelNavigation.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentBack by rememberUpdatedState(onBack)
    DisposableEffect(navigation,depth){val token=Any();navigation?.add(token,depth){if(currentEnabled){currentBack();true}else false};onDispose{navigation?.remove(token)}}
}
