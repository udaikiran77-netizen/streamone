package com.streamone.app.extensions

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.streamone.app.engine.Post
import com.streamone.app.engine.StreamItem
import com.streamone.app.engine.TextTrack
import kotlinx.coroutines.withTimeoutOrNull

/** One homepage section of an extension provider. */
data class ExtSection(
    val title: String,
    val posts: List<Post>
)

/** One playable entry (episode or movie) of an extension detail page. */
data class ExtEpisode(
    val title: String,
    /** The opaque `data` string handed back to MainAPI.loadLinks. */
    val data: String
)

/** Detail info of an extension title, shaped for StreamOne's detail screen. */
data class ExtDetail(
    val title: String,
    val image: String,
    val poster: String?,
    val synopsis: String,
    val type: String,
    val rating: String?,
    val year: Int?,
    val tags: List<String>,
    val cast: List<String>,
    val episodes: List<ExtEpisode>
)

/**
 * Wraps one CloudStream [MainAPI] (registered by a loaded .cs3 plugin) and
 * exposes it through the same shapes the StreamOne UI already uses for Vega
 * providers ([Post], [StreamItem]), so both engines share the poster grid,
 * detail and player screens.
 */
class ExtensionBridge(
    val api: MainAPI,
    /** Absolute path of the .cs3 this provider came from ("" if unknown). */
    val pluginPath: String
) {
    /** Stable id used for navigation state. */
    val id: String = (api.sourcePlugin ?: pluginPath) + "|" + api.name

    val displayName: String get() = api.name
    val language: String get() = api.lang

    private fun SearchResponse.toPost(): Post = Post(
        title = name,
        link = url,
        image = posterUrl ?: "",
        tag = type?.name
    )

    /** Homepage sections (one per MainPageData entry), failures skipped per-section. */
    suspend fun homeSections(): List<ExtSection> {
        if (!api.hasMainPage) return emptyList()
        val sections = ArrayList<ExtSection>()
        for (data in api.mainPage) {
            try {
                val response = api.getMainPage(
                    1,
                    MainPageRequest(data.name, data.data, data.horizontalImages)
                ) ?: continue
                for (list in response.items) {
                    if (list.list.isEmpty()) continue
                    sections.add(
                        ExtSection(
                            title = list.name,
                            posts = list.list.map { it.toPost() }
                        )
                    )
                }
            } catch (e: Exception) {
                // A single broken section must not kill the whole homepage.
                continue
            }
        }
        return sections
    }

    suspend fun search(query: String): List<Post> {
        val results = try {
            api.search(query)
        } catch (e: NotImplementedError) {
            null
        } ?: return emptyList()
        return results.map { it.toPost() }
    }

    suspend fun details(url: String): ExtDetail {
        val response = api.load(url)
            ?: throw IllegalStateException("Provider returned no details")
        return response.toExtDetail()
    }

    private fun episodeLabel(episode: Episode): String {
        val parts = ArrayList<String>()
        if (episode.season != null && episode.episode != null) {
            parts.add("S${episode.season} E${episode.episode}")
        } else if (episode.episode != null) {
            parts.add("Episode ${episode.episode}")
        }
        if (!episode.name.isNullOrBlank()) {
            parts.add(episode.name!!)
        }
        return if (parts.isEmpty()) "Episode" else parts.joinToString(" • ")
    }

    private fun LoadResponse.toExtDetail(): ExtDetail {
        val episodes: List<ExtEpisode> = when (this) {
            is MovieLoadResponse -> listOf(ExtEpisode("▶ Play", dataUrl))
            is LiveStreamLoadResponse -> listOf(ExtEpisode("▶ Play", dataUrl))
            is TvSeriesLoadResponse -> episodes.map { ExtEpisode(episodeLabel(it), it.data) }
            is AnimeLoadResponse -> episodes.flatMap { (dubStatus, eps) ->
                eps.map { episode ->
                    ExtEpisode("${dubStatus.name} • ${episodeLabel(episode)}", episode.data)
                }
            }

            else -> emptyList()
        }
        return ExtDetail(
            title = name,
            image = posterUrl ?: "",
            poster = backgroundPosterUrl,
            synopsis = plot ?: "",
            type = type.name,
            rating = score?.toString(),
            year = year,
            tags = tags ?: emptyList(),
            cast = actors?.map { it.actor.name } ?: emptyList(),
            episodes = episodes
        )
    }

    /**
     * Resolves playable streams for an episode/movie `data` string via
     * MainAPI.loadLinks, collecting every ExtractorLink the provider (and the
     * built-in extractors it delegates to) produces.
     */
    suspend fun streams(data: String): List<StreamItem> {
        val links = ArrayList<ExtractorLink>()
        val subtitles = ArrayList<SubtitleFile>()
        val timeout = api.loadLinksTimeoutMs ?: 120_000L
        withTimeoutOrNull(timeout) {
            api.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { subtitle -> synchronized(subtitles) { subtitles.add(subtitle) } },
                callback = { link -> synchronized(links) { links.add(link) } }
            )
        }
        val tracks = subtitles.map { subtitle ->
            TextTrack(
                title = subtitle.lang,
                language = subtitle.lang,
                type = "text/vtt",
                uri = subtitle.url
            )
        }
        val out = ArrayList<StreamItem>()
        for (link in links) {
            if (link is ExtractorLinkPlayList) {
                for ((index, item) in link.playlist.withIndex()) {
                    if (item.url.isBlank()) continue
                    out.add(
                        StreamItem(
                            server = "${link.source} ${index + 1}",
                            link = item.url,
                            type = link.type.toStreamType(),
                            quality = link.quality.takeIf { it > 0 }?.toString(),
                            headers = link.getAllHeaders(),
                            subtitles = tracks
                        )
                    )
                }
            } else {
                if (link.url.isBlank()) continue
                out.add(
                    StreamItem(
                        server = link.source.ifBlank { link.name },
                        link = link.url,
                        type = link.type.toStreamType(),
                        quality = link.quality.takeIf { it > 0 }?.toString(),
                        headers = link.getAllHeaders(),
                        subtitles = tracks
                    )
                )
            }
        }
        return out
    }

    private fun ExtractorLinkType.toStreamType(): String = when (this) {
        ExtractorLinkType.M3U8 -> "m3u8"
        ExtractorLinkType.DASH -> "dash"
        ExtractorLinkType.VIDEO -> "video"
        ExtractorLinkType.TORRENT -> "torrent"
        ExtractorLinkType.MAGNET -> "magnet"
    }
}
