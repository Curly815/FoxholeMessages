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
package dev.octoshrimpy.quik.feature.conversations

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import dev.octoshrimpy.quik.databinding.ConversationsTabPageBinding

/**
 * Backs the Inbox's per-category ViewPager2 (Personal/Transactions/Promotions/Starred). Each
 * page gets its own [ConversationsAdapter] and [ConversationItemTouchCallback] instance so that
 * selection state and swipe actions stay independent per tab.
 */
class ConversationsPagerAdapter(
    private val pages: List<TabPage>
) : RecyclerView.Adapter<ConversationsPagerAdapter.PageViewHolder>() {

    data class TabPage(
        val tab: Tab,
        val adapter: ConversationsAdapter,
        val touchCallback: ConversationItemTouchCallback
    )

    class PageViewHolder(val binding: ConversationsTabPageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemCount(): Int = pages.size

    override fun getItemViewType(position: Int): Int = position

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ConversationsTabPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        holder.binding.recyclerView.adapter = page.adapter
        page.adapter.emptyView = holder.binding.empty
        page.touchCallback.adapter = page.adapter
        ItemTouchHelper(page.touchCallback).attachToRecyclerView(holder.binding.recyclerView)
    }
}
