package com.lagradost.cloudstream3.utils

import androidx.annotation.StringRes

/**
 * Minimal stand-in for the CloudStream app module's UiText (see
 * PHASE4-NOTES.md). In upstream CloudStream this lives in the app module and
 * is only reachable from library code through VideoClickAction; StreamOne
 * stubs it so Plugin.registerVideoClickAction keeps its exact signature.
 * Extensions that subclass VideoClickAction and rely on the full app-side
 * action UI are not supported.
 */
sealed class UiText {
    data class DynamicString(val value: String) : UiText()

    class StringResource(
        @StringRes val resId: Int,
        vararg val args: Any
    ) : UiText()
}
