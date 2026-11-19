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
import dev.octoshrimpy.quik.databinding.BlockingControllerBinding
import androidx.appcompat.app.AlertDialog
import io.reactivex.Single
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.schedulers.Schedulers
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

    // Both transfers run on io and read nothing from the controller's activity there: the activity
    // can be replaced (rotation) or gone (user left) mid-transfer. The resolver comes from the
    // application context, captured up front; results are shown on the main thread against
    // whatever activity is current by then.
    private fun exportRules(uri: Uri) {
        val appContext = applicationContext ?: return
        val resolver = appContext.contentResolver
        Single.fromCallable {
            val json = BlockingRulesFile.toJson(blockingRepository.exportBlockedAddresses(), filterRepository.exportFilters())
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(json) }
                ?: error("Unable to open destination")
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                Toast.makeText(appContext, R.string.blocking_rules_exported, Toast.LENGTH_SHORT).show()
            }, { error ->
                Timber.e(error, "blocking rules export failed")
                Toast.makeText(appContext, R.string.blocking_rules_export_error, Toast.LENGTH_LONG).show()
            })
    }

    private fun importRules(uri: Uri) {
        val appContext = applicationContext ?: return
        val resolver = appContext.contentResolver
        Single.fromCallable {
            val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                val text = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    require(text.length + count <= BlockingRulesFile.MAX_FILE_CHARS) { "File too large" }
                    text.append(buffer, 0, count)
                }
                text.toString()
            } ?: error("Unable to open file")
            val parsed = BlockingRulesFile.parse(raw)
            val (addedNumbers, addedFilters) = blockingRepository.importBlockingRules(parsed.numbers, parsed.filters)
            Triple(addedNumbers, addedFilters, parsed.skipped)
        }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({ (numbers, filters, skipped) ->
                val res = appContext.resources
                var message = res.getString(R.string.blocking_rules_imported,
                    res.getQuantityString(R.plurals.blocking_rules_numbers, numbers, numbers),
                    res.getQuantityString(R.plurals.blocking_rules_filters, filters, filters))
                if (skipped > 0) message += "\n\n" + res.getQuantityString(R.plurals.blocking_rules_skipped, skipped, skipped)

                // A dialog rather than a toast: Android 12+ cuts toasts off after two lines.
                activity?.let { activity ->
                    AlertDialog.Builder(activity)
                        .setTitle(R.string.blocking_rules_imported_title)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } ?: Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }, { error ->
                Timber.e(error, "blocking rules import failed")
                Toast.makeText(appContext, R.string.blocking_rules_import_error, Toast.LENGTH_LONG).show()
            })
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
