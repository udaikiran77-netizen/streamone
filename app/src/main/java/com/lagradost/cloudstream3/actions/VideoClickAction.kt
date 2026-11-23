package com.lagradost.cloudstream3.actions

import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText

/**
 * Minimal stand-ins for the CloudStream app module's video click actions (see
 * PHASE4-NOTES.md). Upstream, VideoClickAction is bound to the app's player
 * UI (activities, dialogs, external player intents); StreamOne only needs the
 * type to exist with the same shape so that
 * [com.lagradost.cloudstream3.plugins.Plugin.registerVideoClickAction] keeps
 * its exact signature and plugins calling it do not crash. Registered actions
 * are stored but never executed by StreamOne's UI.
 */
abstract class VideoClickAction {
    abstract val name: UiText

    /** if true, the app will show dialog to select source - result.links[index] */
    open val oneSource: Boolean = false

    /** if true, this action could be selected as default player (one press action) in settings */
    open val isPlayer: Boolean = false

    /** Which type of sources this action can handle. */
    open val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    /** Determines which plugin a given provider is from. This is the full path to the plugin. */
    var sourcePlugin: String? = null
}

object VideoClickActionHolder {
    val allVideoClickActions = atomicListOf<VideoClickAction>()
}
