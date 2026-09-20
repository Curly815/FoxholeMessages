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
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.base.QkRealmAdapter
import dev.octoshrimpy.quik.databinding.ConversationsTabPageBinding

/**
 * Backs the Inbox's per-category ViewPager2 (Personal/Transactions/Promotions/Starred).
 *
 * The three category pages list conversations, each with its own [ConversationsAdapter] and
 * [ConversationItemTouchCallback] instance so that selection state and swipe actions stay
 * independent per tab. Starred is a different shape entirely - it lists starred messages rather
 * than conversations - so it carries no selection or swipe behaviour, which is why the two page
 * kinds are modelled separately rather than forced through one adapter type.
 */
class ConversationsPagerAdapter(
    private val pages: List<TabPage>
) : RecyclerView.Adapter<ConversationsPagerAdapter.PageViewHolder>() {

    sealed class TabPage {

        abstract val tab: Tab

        /** The adapter to attach, regardless of what the page lists. */
        abstract val adapter: QkRealmAdapter<*, *>

        data class Conversations(
            override val tab: Tab,
            val conversationsAdapter: ConversationsAdapter,
            val touchCallback: ConversationItemTouchCallback
        ) : TabPage() {
            override val adapter: QkRealmAdapter<*, *> get() = conversationsAdapter
        }

        data class Starred(
            override val tab: Tab,
            val starredAdapter: StarredMessagesAdapter
        ) : TabPage() {
            override val adapter: QkRealmAdapter<*, *> get() = starredAdapter
        }
    }

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

        when (page) {
            is TabPage.Conversations -> {
                page.touchCallback.adapter = page.conversationsAdapter
                ItemTouchHelper(page.touchCallback).attachToRecyclerView(holder.binding.recyclerView)
            }

            // The shared empty view talks about conversations, which is wrong for a message list
            is TabPage.Starred ->
                holder.binding.empty.setText(R.string.tab_starred_empty)
        }
    }
}
