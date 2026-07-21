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
package dev.octoshrimpy.quik.feature.plus

import android.graphics.Typeface
import android.os.Bundle
import androidx.core.view.children
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import com.jakewharton.rxbinding2.view.clicks
import dagger.android.AndroidInjection
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkThemedActivity
import dev.octoshrimpy.quik.common.util.FontProvider
import dev.octoshrimpy.quik.common.util.extensions.resolveThemeColor
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.common.widget.PreferenceView
import dev.octoshrimpy.quik.databinding.QksmsPlusActivityBinding
import javax.inject.Inject

class PlusActivity : QkThemedActivity(), PlusView {

    @Inject lateinit var fontProvider: FontProvider
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory

    private val viewModel by lazy { ViewModelProviders.of(this, viewModelFactory)[PlusViewModel::class.java] }
    private lateinit var binding: QksmsPlusActivityBinding

    override val donateVenmoIntent get() = binding.donateVenmo.clicks()
    override val themeClicks get() = binding.themes.clicks()
    override val scheduleClicks get() = binding.schedule.clicks()
    override val backupClicks get() = binding.backup.clicks()
    override val delayedClicks get() = binding.delayed.clicks()
    override val nightClicks get() = binding.night.clicks()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = QksmsPlusActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.plus_activity_title)
        showBackButton(true)
        viewModel.bindView(this)

        if (!prefs.systemFont.get()) {
            fontProvider.getLato { lato ->
                val typeface = Typeface.create(lato, Typeface.BOLD)
                binding.toolbarLayout.collapsingToolbar.setCollapsedTitleTypeface(typeface)
                binding.toolbarLayout.collapsingToolbar.setExpandedTitleTypeface(typeface)
            }
        }

        // Make the list titles bold
        binding.linearLayout.children
            .mapNotNull { it as? PreferenceView }
            .map { it.titleView }
            .forEach { it.setTypeface(it.typeface, Typeface.BOLD) }

        val textPrimary = resolveThemeColor(android.R.attr.textColorPrimary)
        binding.toolbarLayout.collapsingToolbar.setCollapsedTitleTextColor(textPrimary)
        binding.toolbarLayout.collapsingToolbar.setExpandedTitleColor(textPrimary)

        val theme = colors.theme().theme
        binding.donateVenmo.setBackgroundTint(theme)
    }

    override fun render(state: PlusState) {
        // Every feature listed here is already unlocked; nothing to render conditionally.
    }

}
