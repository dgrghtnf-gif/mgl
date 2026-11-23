package com.pickle.tabletfilemanager

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid of large file cards. Selection state lives here and is read back by the activity.
 */
class FileGridAdapter(
    private val onClick: (FileItem) -> Unit,
    private val onLongClick: (FileItem) -> Unit,
    private val onToggle: (FileItem) -> Unit
) : RecyclerView.Adapter<FileGridAdapter.ItemViewHolder>() {

    private val items = ArrayList<FileItem>()
    private val selected = LinkedHashSet<String>()

    fun submit(list: List<FileItem>) {
        items.clear()
        items.addAll(list)
        val visible = list.map { it.path }.toSet()
        selected.retainAll(visible)
        notifyDataSetChanged()
    }

    fun clearSelection() {
        if (selected.isEmpty()) return
        selected.clear()
        notifyDataSetChanged()
    }

    fun selectedItems(): List<FileItem> = items.filter { selected.contains(it.path) }

    fun toggle(item: FileItem) {
        if (!selected.remove(item.path)) selected.add(item.path)
        val index = items.indexOfFirst { it.path == item.path }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.name
        holder.meta.text = metaText(item)
        holder.badge.text = FileKinds.labelOf(item)
        holder.badge.background = badgeDrawable(FileKinds.colorOf(item))

        val isSelected = selected.contains(item.path)
        holder.card.isSelected = isSelected
        holder.check.isSelected = isSelected
        holder.check.text = if (isSelected) "✓" else ""
        holder.check.setBackgroundResource(if (isSelected) R.drawable.bg_check_on else R.drawable.bg_check_off)
        holder.check.setTextColor(Color.WHITE)

        holder.card.setOnClickListener { onClick(item) }
        holder.card.setOnLongClickListener {
            onLongClick(item)
            true
        }
        holder.check.setOnClickListener { onToggle(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun metaText(item: FileItem): String {
        return if (item.isDir) {
            "폴더 · ${item.childCount}개 항목"
        } else {
            "${FileKinds.kindNameOf(item)} · ${FileOps.formatSize(item.size)}"
        }
    }

    private fun badgeDrawable(color: Int): GradientDrawable {
        val density = Resources.getSystem().displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f * density
            setColor(color)
        }
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.cardRoot)
        val badge: TextView = view.findViewById(R.id.badge)
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
        val check: TextView = view.findViewById(R.id.checkBox)
    }
}
