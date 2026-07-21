/*
 * Copyright (C) 2026 Foxhole Messages contributors
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.octoshrimpy.quik.common.util

import io.reactivex.Maybe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.rx2.rxMaybe
import me.saket.unfurl.Unfurler
import javax.inject.Inject
import javax.inject.Singleton

data class LinkPreview(
    val url: String,
    val title: String?,
    val description: String?,
    val thumbnailUrl: String?,
    val faviconUrl: String?
)

/**
 * Fetches Open Graph / Twitter Card metadata for URLs found in messages, to render as a
 * thumbnail preview card. Backed by [Unfurler]'s own in-memory LRU cache (keyed by URL), since
 * this app uses Realm rather than Room and a persistent cache isn't worth the complexity here.
 *
 * Any failure (timeout, no connection, blocked host, unparsable page) or a page with no usable
 * metadata surfaces as an empty [Maybe] rather than throwing, so callers can fall back to
 * rendering the message as a plain link.
 */
@Singleton
class LinkPreviewRepository @Inject constructor() {

    private val unfurler = Unfurler()

    fun unfurl(url: String): Maybe<LinkPreview> = rxMaybe(Dispatchers.IO) {
        unfurler.unfurl(url)?.let { result ->
            LinkPreview(
                url = result.url.toString(),
                title = result.title?.takeIf { it.isNotBlank() },
                description = result.description?.takeIf { it.isNotBlank() },
                thumbnailUrl = result.thumbnail?.toString(),
                faviconUrl = result.favicon?.toString()
            )
        }?.takeIf { it.title != null || it.thumbnailUrl != null }
    }

}
