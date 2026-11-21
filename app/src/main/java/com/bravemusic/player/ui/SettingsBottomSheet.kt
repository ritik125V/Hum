package com.bravemusic.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.bravemusic.player.databinding.DialogSettingsBinding
import com.bravemusic.player.utils.IconManager
import com.bravemusic.player.utils.SettingsManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class SettingsBottomSheet(
    private val onSettingsChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SettingsManager
    private var selectedIconKey: String = "blob"

    private val artworkOptions = listOf(
        "🎵 Original Album Art",
        "🔥 Neon Coral Glow",
        "⚡ Cyan Titanium",
        "⬛ Minimalist Black"
    )

    private val artworkKeys = listOf(
        "default",
        "brave",
        "cyberpunk",
        "minimal"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsManager(requireContext())
        selectedIconKey = IconManager.normalizeKey(settings.appIcon)

        setupLauncherAndIcons()
        setupNotificationPlayer()
        setupUpdatesSection()

        // Apply
        binding.btnSaveSettings.setOnClickListener {
            saveAllSettings()
        }
    }

    private fun setupUpdatesSection() {
        binding.tvCurrentVersion.text = "v${com.bravemusic.player.BuildConfig.VERSION_NAME}"
        binding.btnCheckForUpdates.setOnClickListener {
            val currentVersion = com.bravemusic.player.BuildConfig.VERSION_NAME
            val updateUrl = "https://hum.r8r.in/?version=$currentVersion"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Opening update link...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupLauncherAndIcons() {
        binding.tvAppNameFixed.text = IconManager.APP_NAME
        binding.tvActiveIconLabel.text = IconManager.getStyleName(selectedIconKey)

        val adapter = IconStyleAdapter(selectedIconKey) { style ->
            selectedIconKey = style.id
            binding.tvActiveIconLabel.text = style.styleName
        }

        binding.rvIconStyles.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        binding.rvIconStyles.adapter = adapter
        binding.rvIconStyles.setHasFixedSize(true)

        // Scroll to currently selected icon
        val currentIndex = IconManager.STYLES.indexOfFirst { it.id == selectedIconKey }
        if (currentIndex > 0) {
            binding.rvIconStyles.scrollToPosition(currentIndex)
        }

        binding.btnPinShortcut.setOnClickListener {
            val success = IconManager.pinCustomShortcut(requireContext(), selectedIconKey)
            if (success) {
                Toast.makeText(requireContext(), "Home screen shortcut 'Hum' created", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "Pinning 'Hum' to home screen...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupNotificationPlayer() {
        binding.switchCustomMetadata.isChecked = settings.isCustomMetadataEnabled
        binding.layoutCustomFields.visibility = if (settings.isCustomMetadataEnabled) View.VISIBLE else View.GONE
        binding.etCustomTitle.setText(settings.customTitle.ifEmpty { "Focus Audio" })
        binding.etCustomArtist.setText(settings.customArtist.ifEmpty { IconManager.APP_NAME })

        binding.switchCustomMetadata.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutCustomFields.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, artworkOptions)
        binding.actvArtwork.setAdapter(adapter)

        val currentIndex = artworkKeys.indexOf(settings.artworkPreset).coerceAtLeast(0)
        binding.actvArtwork.setText(artworkOptions[currentIndex], false)
    }

    private fun saveAllSettings() {
        settings.isCustomMetadataEnabled = binding.switchCustomMetadata.isChecked
        settings.customTitle = binding.etCustomTitle.text?.toString()?.ifEmpty { "Focus Audio" } ?: "Focus Audio"
        settings.customArtist = binding.etCustomArtist.text?.toString()?.ifEmpty { IconManager.APP_NAME } ?: IconManager.APP_NAME

        val selectedText = binding.actvArtwork.text.toString()
        val artIndex = artworkOptions.indexOf(selectedText)
        if (artIndex >= 0) {
            settings.artworkPreset = artworkKeys[artIndex]
        }

        settings.customLauncherName = IconManager.APP_NAME

        if (selectedIconKey != settings.appIcon) {
            settings.appIcon = selectedIconKey
            IconManager.applyAppIcon(requireContext(), selectedIconKey)
        }

        Toast.makeText(requireContext(), "Settings applied!", Toast.LENGTH_SHORT).show()
        onSettingsChanged()
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "SettingsBottomSheet"
    }
}
