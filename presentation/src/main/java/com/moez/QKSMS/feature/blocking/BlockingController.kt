/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
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
package dev.octoshrimpy.quik.feature.blocking

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.ActivityResultLauncher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bluelinelabs.conductor.RouterTransaction
import com.jakewharton.rxbinding2.view.clicks
import com.uber.autodispose.android.lifecycle.scope
import com.uber.autodispose.autoDisposable
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.QkChangeHandler
import dev.octoshrimpy.quik.common.base.QkController
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.animateLayoutChanges
import dev.octoshrimpy.quik.feature.blocking.messages.BlockedMessagesController
import dev.octoshrimpy.quik.feature.blocking.numbers.BlockedNumbersController
import dev.octoshrimpy.quik.feature.blocking.filters.MessageContentFiltersController
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.repository.BlockingRepository
import dev.octoshrimpy.quik.repository.MessageContentFilterRepository
import dev.octoshrimpy.quik.model.MessageContentFilterData
import dev.octoshrimpy.quik.databinding.BlockingControllerBinding
import io.reactivex.Observable
import io.reactivex.schedulers.Schedulers
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

class BlockingController : QkController<BlockingControllerBinding, BlockingView, BlockingState, BlockingPresenter>(), BlockingView {

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup): BlockingControllerBinding =
        BlockingControllerBinding.inflate(inflater, container, false)

    override val blockedNumbersIntent get() = binding.blockedNumbers.clicks()
    override val messageContentFiltersIntent get() = binding.messageContentFilters.clicks()
    override val blockedMessagesIntent get() = binding.blockedMessages.clicks()
    override val dropClickedIntent get() = binding.drop.clicks()
    private val exportClicks get() = binding.exportBlockingRules.clicks()
    private val importClicks get() = binding.importBlockingRules.clicks()

    @Inject lateinit var colors: Colors
    @Inject lateinit var blockingRepository: BlockingRepository
    @Inject lateinit var filterRepository: MessageContentFilterRepository
    @Inject override lateinit var presenter: BlockingPresenter
    private lateinit var createDocument: ActivityResultLauncher<String>
    private lateinit var openDocument: ActivityResultLauncher<Array<String>>

    override fun onContextAvailable(context: android.content.Context) {
        createDocument = themedActivity!!.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) exportRules(uri)
        }
        openDocument = themedActivity!!.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importRules(uri)
        }
    }

    init {
        appComponent.inject(this)
        retainViewMode = RetainViewMode.RETAIN_DETACH
    }

    override fun onViewCreated() {
        super.onViewCreated()
        binding.parent.postDelayed({ binding.parent.animateLayoutChanges = true }, 100)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        presenter.bindIntents(this)
        exportClicks.autoDisposable(scope()).subscribe { createDocument.launch("foxhole-blocking-rules.json") }
        importClicks.autoDisposable(scope()).subscribe { openDocument.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }
        setTitle(R.string.blocking_title)
        showBackButton(true)
    }

    private fun exportRules(uri: Uri) {
        Observable.fromCallable {
            val numbers = blockingRepository.exportBlockedAddresses()
            val filters = filterRepository.exportFilters()
            val json = JSONObject().put("format", "foxhole-blocking-rules").put("version", 1)
                .put("blockedNumbers", JSONArray(numbers))
                .put("filters", JSONArray().apply {
                    filters.forEach { filter -> put(JSONObject().put("value", filter.value)
                        .put("caseSensitive", filter.caseSensitive).put("isRegex", filter.isRegex)
                        .put("includeContacts", filter.includeContacts)) }
                }).toString()
            requireNotNull(activity).contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                ?: error("Unable to open destination")
        }.subscribeOn(Schedulers.io()).subscribe({
            activity?.runOnUiThread { Toast.makeText(activity, R.string.blocking_rules_exported, Toast.LENGTH_SHORT).show() }
        }, { error ->
            Timber.e(error, "blocking rules export failed")
            showTransferError(R.string.blocking_rules_export_error)
        })
    }

    private fun importRules(uri: Uri) {
        Observable.fromCallable {
            val raw = requireNotNull(activity).contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= 2 * 1024 * 1024) { "File too large" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            } ?: error("Unable to open file")
            val root = JSONObject(raw)
            require(root.optString("format") == "foxhole-blocking-rules" && root.optInt("version") == 1) { "Unsupported format" }
            val numberArray = root.getJSONArray("blockedNumbers")
            val numbers = (0 until numberArray.length()).map { numberArray.getString(it).trim() }
            require(numbers.all { it.isNotBlank() && it.length <= 100 }) { "Invalid number" }
            val filterArray = root.getJSONArray("filters")
            // A structurally bad file is rejected as a whole, but an invalid regex is skipped: older
            // builds let one be saved and exported, and failing on it would make that whole backup
            // impossible to restore.
            var skippedRegexes = 0
            val filters = (0 until filterArray.length()).mapNotNull { index ->
                val item = filterArray.getJSONObject(index)
                require(item.get("caseSensitive") is Boolean && item.get("isRegex") is Boolean && item.get("includeContacts") is Boolean)
                val value = item.getString("value")
                require(value.isNotBlank() && value.length <= 1000)
                val regex = item.getBoolean("isRegex")
                if (regex && runCatching { Regex(value) }.isFailure) {
                    skippedRegexes++
                    return@mapNotNull null
                }
                MessageContentFilterData(value, item.getBoolean("caseSensitive") && !regex, regex, item.getBoolean("includeContacts"))
            }
            val addedNumbers = blockingRepository.importBlockedAddresses(numbers)
            val addedFilters = filterRepository.importFilters(filters)
            Triple(addedNumbers, addedFilters, skippedRegexes)
        }.subscribeOn(Schedulers.io()).subscribe({ (numbers, filters, skipped) ->
            activity?.let { activity ->
                val message = if (skipped == 0) activity.getString(R.string.blocking_rules_imported, numbers, filters)
                else activity.getString(R.string.blocking_rules_imported_skipped, numbers, filters, skipped)
                activity.runOnUiThread { Toast.makeText(activity, message, Toast.LENGTH_LONG).show() }
            }
        }, { error ->
            Timber.e(error, "blocking rules import failed")
            showTransferError(R.string.blocking_rules_import_error)
        })
    }

    private fun showTransferError(messageRes: Int) {
        activity?.runOnUiThread { Toast.makeText(activity, messageRes, Toast.LENGTH_LONG).show() }
    }

    override fun render(state: BlockingState) {
        binding.drop.checkbox?.isChecked = state.dropEnabled
        binding.blockedMessages.isEnabled = !state.dropEnabled
    }

    override fun openBlockedNumbers() {
        router.pushController(RouterTransaction.with(BlockedNumbersController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

    override fun openMessageContentFilters() {
        router.pushController(RouterTransaction.with(MessageContentFiltersController())
            .pushChangeHandler(QkChangeHandler())
            .popChangeHandler(QkChangeHandler()))
    }

    override fun openBlockedMessages() {
        router.pushController(RouterTransaction.with(BlockedMessagesController())
                .pushChangeHandler(QkChangeHandler())
                .popChangeHandler(QkChangeHandler()))
    }

}
