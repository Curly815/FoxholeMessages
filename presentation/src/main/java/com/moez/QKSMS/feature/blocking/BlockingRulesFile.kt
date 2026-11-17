/*
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
package dev.octoshrimpy.quik.feature.blocking

import dev.octoshrimpy.quik.model.MessageContentFilterData
import org.json.JSONArray
import org.json.JSONObject

/**
 * The blocking-rules export file: a JSON object holding blocked numbers and message content filters.
 *
 * Export writes exactly what is saved. Import is the single validation point, because a file can come
 * from anywhere (an older build, a hand edit): a file that isn't a rules file at all is rejected as a
 * whole, but individual bad entries are skipped and counted, so one bad rule never makes an entire
 * backup impossible to restore.
 */
object BlockingRulesFile {

    const val MAX_FILE_CHARS = 2 * 1024 * 1024
    const val MAX_ENTRIES = 2000
    const val MAX_NUMBER_LENGTH = 100
    const val MAX_FILTER_LENGTH = 1000

    private const val FORMAT = "foxhole-blocking-rules"
    private const val VERSION = 1

    data class Parsed(
        val numbers: List<String>,
        val filters: List<MessageContentFilterData>,
        val skipped: Int
    )

    fun toJson(numbers: List<String>, filters: List<MessageContentFilterData>): String =
        JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("blockedNumbers", JSONArray(numbers))
            .put("filters", JSONArray().apply {
                filters.forEach { filter ->
                    put(JSONObject()
                        .put("value", filter.value)
                        .put("caseSensitive", filter.caseSensitive)
                        .put("isRegex", filter.isRegex)
                        .put("includeContacts", filter.includeContacts))
                }
            })
            .toString()

    /** Throws IllegalArgumentException (or JSONException) if [raw] is not a usable rules file. */
    fun parse(raw: String): Parsed {
        // org.json parses nested arrays/objects recursively, so a hostile file nested deeply enough
        // overflows the stack. RxJava treats StackOverflowError as fatal and rethrows it, which would
        // crash the app, so turn it into an ordinary parse failure here.
        val root = try {
            JSONObject(raw)
        } catch (e: StackOverflowError) {
            throw IllegalArgumentException("File is nested too deeply")
        }
        require(root.optString("format") == FORMAT && root.optInt("version") == VERSION) { "Unsupported format" }

        val numberArray = root.getJSONArray("blockedNumbers")
        val filterArray = root.getJSONArray("filters")
        require(numberArray.length() <= MAX_ENTRIES && filterArray.length() <= MAX_ENTRIES) { "Too many entries" }

        var skipped = 0

        val numbers = (0 until numberArray.length()).mapNotNull { index ->
            val number = (numberArray.opt(index) as? String)?.trim()
            if (number == null || !isValidNumber(number)) {
                skipped++
                null
            } else number
        }

        val filters = (0 until filterArray.length()).mapNotNull { index ->
            val item = filterArray.optJSONObject(index)
            val value = item?.opt("value") as? String
            val caseSensitive = item?.opt("caseSensitive") as? Boolean
            val isRegex = item?.opt("isRegex") as? Boolean
            val includeContacts = item?.opt("includeContacts") as? Boolean
            if (value == null || caseSensitive == null || isRegex == null || includeContacts == null ||
                filterProblem(value, isRegex) != null) {
                skipped++
                null
            } else {
                // Same coercion as the create dialog: case sensitivity doesn't apply to regex filters.
                MessageContentFilterData(value, caseSensitive && !isRegex, isRegex, includeContacts)
            }
        }

        return Parsed(numbers, filters, skipped)
    }

    fun isValidNumber(number: String): Boolean = number.isNotBlank() && number.length <= MAX_NUMBER_LENGTH

    enum class FilterProblem { BLANK, TOO_LONG, INVALID_REGEX }

    /** Why a content filter can't be saved, or null if it can. Shared by the create dialog and import. */
    fun filterProblem(value: String, isRegex: Boolean): FilterProblem? = when {
        value.isBlank() -> FilterProblem.BLANK
        value.length > MAX_FILTER_LENGTH -> FilterProblem.TOO_LONG
        isRegex && runCatching { Regex(value) }.isFailure -> FilterProblem.INVALID_REGEX
        else -> null
    }
}
