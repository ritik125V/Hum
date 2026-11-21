package com.bravemusic.player.ui.feed

import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.bravemusic.player.R
import com.bravemusic.player.data.feed.FeedSection
import com.bravemusic.player.data.feed.MediaItem
import com.bravemusic.player.databinding.LayoutFeedItemBinding
import com.bravemusic.player.databinding.LayoutFeedSectionBinding

class CarouselItemAdapter(
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<CarouselItemAdapter.ItemViewHolder>() {

    private val items = mutableListOf<MediaItem>()

    fun setItems(newItems: List<MediaItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = LayoutFeedItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ItemViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ItemViewHolder(private val binding: LayoutFeedItemBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MediaItem) {
            binding.tvTitle.text = item.title
            binding.tvSubtitle.text = item.subtitle.ifBlank { "Song" }

            if (item.thumbnailUrl.isNotBlank() && !item.thumbnailUrl.startsWith("data:")) {
                binding.ivCover.load(item.thumbnailUrl) {
                    crossfade(true)
                    placeholder(R.drawable.rounded_artwork_placeholder)
                    error(R.drawable.rounded_artwork_placeholder)
                }
            } else {
                binding.ivCover.setImageResource(R.drawable.rounded_artwork_placeholder)
            }

            binding.root.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onItemClick(item)
            }
        }
    }
}

class FeedSectionAdapter(
    private val onItemClick: (MediaItem) -> Unit
) : RecyclerView.Adapter<FeedSectionAdapter.SectionViewHolder>() {

    private val sections = mutableListOf<FeedSection>()
    private val sharedViewPool = RecyclerView.RecycledViewPool()

    fun updateSections(newSections: List<FeedSection>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = sections.size
            override fun getNewListSize(): Int = newSections.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return sections[oldItemPosition].id == newSections[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return sections[oldItemPosition] == newSections[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        sections.clear()
        sections.addAll(newSections)
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SectionViewHolder {
        val binding = LayoutFeedSectionBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        binding.rvCarousel.setRecycledViewPool(sharedViewPool)
        return SectionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SectionViewHolder, position: Int) {
        holder.bind(sections[position])
    }

    override fun getItemCount(): Int = sections.size

    inner class SectionViewHolder(private val binding: LayoutFeedSectionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val carouselAdapter = CarouselItemAdapter(onItemClick)

        init {
            binding.rvCarousel.layoutManager = LinearLayoutManager(
                binding.root.context,
                LinearLayoutManager.HORIZONTAL,
                false
            )
            binding.rvCarousel.adapter = carouselAdapter
            binding.rvCarousel.setHasFixedSize(true)
        }

        fun bind(section: FeedSection) {
            binding.tvSectionTitle.text = section.title
            carouselAdapter.setItems(section.items)
        }
    }
}
