package com.lagradost.api

/**
 * Handwritten replacement for the BuildKonfig-generated BuildConfig used by
 * the vendored CloudStream library (see PHASE4-NOTES.md). The upstream values
 * come from the maintainer's local.properties / CI secrets; StreamOne ships
 * empty keys, so meta providers that need them (MyDramaList, Trakt) simply
 * return no results instead of crashing.
 */
object BuildConfig {
    const val MDL_API_KEY: String = ""
    const val TRAKT_CLIENT_ID: String = ""
}
