package com.example

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

// Tab Model containing key state values
data class BrowserTab(
    val id: String,
    val webView: android.webkit.WebView,
    var title: String = "New Tab",
    var url: String = "about:blank"
)

class TabsAdapter(
    private var tabsList: List<BrowserTab>,
    private var activeTabId: String,
    private val onTabClick: (BrowserTab) -> Unit,
    private val onTabClose: (BrowserTab) -> Unit
) : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

    fun updateData(newTabs: List<BrowserTab>, activeId: String) {
        tabsList = newTabs
        activeTabId = activeId
        notifyDataSetChanged()
    }

    class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tabCard: MaterialCardView = view.findViewById(R.id.tabCard)
        val ivTabIcon: ImageView = view.findViewById(R.id.ivTabIcon)
        val tvTabTitle: TextView = view.findViewById(R.id.tvTabTitle)
        val tvTabUrl: TextView = view.findViewById(R.id.tvTabUrl)
        val btnTabClose: ImageButton = view.findViewById(R.id.btnTabClose)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tab, parent, false)
        return TabViewHolder(view)
    }

    override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
        val tab = tabsList[position]
        
        // Clean display title and url
        holder.tvTabTitle.text = if (tab.title.isNullOrEmpty()) "New Tab" else tab.title
        holder.tvTabUrl.text = if (tab.url.isNullOrEmpty()) "about:blank" else cleanUrlForDisplay(tab.url)

        // Highlight selected tabcard based on theme accent "Natural Tones"
        if (tab.id == activeTabId) {
            holder.tabCard.setStrokeColor(android.graphics.Color.parseColor("#386B20")) // Beautiful Natural Green
            holder.tabCard.strokeWidth = dpToPx(holder.itemView.context, 2)
            holder.tabCard.setCardBackgroundColor(android.graphics.Color.parseColor("#EFF1E0")) // Soft Light Green tint
        } else {
            holder.tabCard.setStrokeColor(android.graphics.Color.parseColor("#E1E3D3")) // Warm neutral border
            holder.tabCard.strokeWidth = dpToPx(holder.itemView.context, 1)
            holder.tabCard.setCardBackgroundColor(android.graphics.Color.parseColor("#FDFCF4")) // Softest Canvas bg
        }

        holder.tabCard.setOnClickListener {
            onTabClick(tab)
        }

        holder.btnTabClose.setOnClickListener {
            onTabClose(tab)
        }
    }

    override fun getItemCount() = tabsList.size

    private fun cleanUrlForDisplay(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        var cleaned = url
        if (cleaned.startsWith("https://")) cleaned = cleaned.substring(8)
        else if (cleaned.startsWith("http://")) cleaned = cleaned.substring(7)
        if (cleaned.startsWith("www.")) cleaned = cleaned.substring(4)
        if (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length - 1)
        return cleaned
    }

    private fun dpToPx(context: android.content.Context, dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return Math.round(dp.toFloat() * density)
    }
}
