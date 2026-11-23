package com.pickle.tabletfilemanager

import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid of large file cards. Selection state lives here and is read back by the activity.
 *
 * Photos and videos show a real thumbnail in the card's preview area (rounded corners,
 * centre-crop) once [ThumbnailLoader] has decoded it; videos also get a translucent play
 * badge. Everything else keeps its Windows Explorer style vector icon. While a preview is
 * loading, or when decoding fails, the type icon stays visible as the placeholder.
 */
class FileGridAdapter(
    private val thumbnails: ThumbnailLoader,
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

        val extensionLabel = FileKinds.labelOf(item)
        if (item.isDir || extensionLabel == "FILE") {
            holder.pill.visibility = View.GONE
        } else {
            holder.pill.visibility = View.VISIBLE
            holder.pill.text = extensionLabel
            holder.pill.setTextColor(FileKinds.colorOf(item))
        }

        holder.name.text = item.name
        holder.meta.text = metaText(item)

        bindPreview(holder, item)

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

    /**
     * Puts the type icon, a cached thumbnail or a queued decode into the preview area.
     * The holder is tagged with the file's cache key so a decode that finishes after the
     * card was recycled is dropped instead of landing on the wrong file.
     */
    private fun bindPreview(holder: ItemViewHolder, item: FileItem) {
        val kind = FileKinds.thumbKindOf(item)
        val key = thumbnails.cacheKey(item.file)
        holder.thumb.tag = key

        holder.icon.setImageResource(FileKinds.iconResOf(item))
        holder.icon.visibility = View.VISIBLE
        holder.thumb.setImageDrawable(null)
        holder.thumb.visibility = View.GONE
        holder.playBadge.visibility = View.GONE

        if (kind == ThumbKind.NONE) return

        val cached = thumbnails.cached(item.file)
        if (cached != null) {
            showThumbnail(holder, cached, kind)
            return
        }
        thumbnails.load(item.file, kind) { loadedKey, bitmap ->
            if (loadedKey == holder.thumb.tag && bitmap != null) {
                showThumbnail(holder, bitmap, kind)
            }
        }
    }

    private fun showThumbnail(holder: ItemViewHolder, bitmap: Bitmap, kind: ThumbKind) {
        holder.thumb.setImageBitmap(bitmap)
        holder.thumb.visibility = View.VISIBLE
        holder.icon.visibility = View.GONE
        holder.playBadge.visibility = if (kind == ThumbKind.VIDEO) View.VISIBLE else View.GONE
    }

    override fun getItemCount(): Int = items.size

    private fun metaText(item: FileItem): String {
        return if (item.isDir) {
            "폴더 · ${item.childCount}개 항목"
        } else {
            "${FileKinds.kindNameOf(item)} · ${FileOps.formatSize(item.size)}"
        }
    }

    class ItemViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.cardRoot)
        val icon: ImageView = view.findViewById(R.id.fileIcon)
        val thumb: ImageView = view.findViewById(R.id.thumbImage)
        val playBadge: ImageView = view.findViewById(R.id.playBadge)
        val pill: TextView = view.findViewById(R.id.extPill)
        val name: TextView = view.findViewById(R.id.fileName)
        val meta: TextView = view.findViewById(R.id.fileMeta)
        val check: TextView = view.findViewById(R.id.checkBox)
    }
}
