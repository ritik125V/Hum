package com.bravemusic.player.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bravemusic.player.R
import com.bravemusic.player.utils.HumIconStyle
import com.bravemusic.player.utils.IconManager
import com.google.android.material.card.MaterialCardView

class IconStyleAdapter(
    private var selectedId: String,
    private val onStyleSelected: (HumIconStyle) -> Unit
) : RecyclerView.Adapter<IconStyleAdapter.ViewHolder>() {

    private val styles = IconManager.STYLES

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: MaterialCardView = view.findViewById(R.id.cardIcon)
        val imgPreview: ImageView = view.findViewById(R.id.imgIconPreview)
        val tvName: TextView = view.findViewById(R.id.tvIconStyleName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_icon_style, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val style = styles[position]
        holder.imgPreview.setImageResource(style.previewRes)
        holder.tvName.text = style.styleName

        val isSelected = style.id == selectedId
        val density = holder.itemView.context.resources.displayMetrics.density
        if (isSelected) {
            holder.card.strokeWidth = (2 * density).toInt()
            holder.card.setStrokeColor(ColorStateList.valueOf(0xFF336BFF.toInt())) // hum_blue
            holder.card.setCardBackgroundColor(0xFF141E38.toInt())
            holder.card.cardElevation = 6f
        } else {
            holder.card.strokeWidth = (1 * density).toInt()
            holder.card.setStrokeColor(ColorStateList.valueOf(0xFF1D2842.toInt()))
            holder.card.setCardBackgroundColor(0xFF0C1220.toInt())
            holder.card.cardElevation = 0f
        }

        holder.card.setOnClickListener {
            if (selectedId != style.id) {
                val prevIndex = styles.indexOfFirst { it.id == selectedId }
                selectedId = style.id
                if (prevIndex != -1) notifyItemChanged(prevIndex)
                notifyItemChanged(position)
                onStyleSelected(style)
            }
        }
    }

    override fun getItemCount(): Int = styles.size

    fun getSelectedId(): String = selectedId

    fun setSelected(id: String) {
        if (selectedId != id) {
            val prevIndex = styles.indexOfFirst { it.id == selectedId }
            selectedId = id
            if (prevIndex != -1) notifyItemChanged(prevIndex)
            val newIndex = styles.indexOfFirst { it.id == id }
            if (newIndex != -1) notifyItemChanged(newIndex)
        }
    }
}
