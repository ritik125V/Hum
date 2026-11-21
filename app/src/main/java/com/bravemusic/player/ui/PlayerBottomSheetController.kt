package com.bravemusic.player.ui

import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import coil.load
import com.bravemusic.player.R
import com.bravemusic.player.databinding.LayoutPlayerBottomSheetBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior

class PlayerBottomSheetController(
    private val binding: LayoutPlayerBottomSheetBinding,
    private val onPlayPauseAction: () -> Unit,
    private val onNextAction: () -> Unit,
    private val onPreviousAction: () -> Unit,
    private val onSeekAction: (seconds: Float) -> Unit,
    private val onCollapseAction: (() -> Unit)? = null
) {
    val behavior: BottomSheetBehavior<FrameLayout> = BottomSheetBehavior.from(binding.playerSheetRoot)

    private var isUserTrackingSeek = false
    private var durationSeconds: Float = 0f
    private var isPlaying: Boolean = false

    init {
        setupBehavior()
        setupListeners()
    }

    private fun setupBehavior() {
        behavior.isHideable = true
        behavior.state = BottomSheetBehavior.STATE_HIDDEN

        behavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        binding.miniplayerContainer.visibility = View.GONE
                        binding.fullPlayerContainer.visibility = View.VISIBLE
                        binding.fullPlayerContainer.alpha = 1f
                    }
                    BottomSheetBehavior.STATE_COLLAPSED -> {
                        binding.miniplayerContainer.visibility = View.VISIBLE
                        binding.miniplayerContainer.alpha = 1f
                        binding.fullPlayerContainer.visibility = View.GONE
                        onCollapseAction?.invoke()
                    }
                    BottomSheetBehavior.STATE_HIDDEN -> {
                        binding.miniplayerContainer.visibility = View.GONE
                        binding.fullPlayerContainer.visibility = View.GONE
                        onCollapseAction?.invoke()
                    }
                    else -> {}
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if (slideOffset >= 0f) {
                    // 0.0 = Collapsed, 1.0 = Expanded
                    // Fade out miniplayer from 0.0 to 0.25
                    val miniAlpha = (1f - (slideOffset / 0.25f)).coerceIn(0f, 1f)
                    binding.miniplayerContainer.alpha = miniAlpha
                    binding.miniplayerContainer.visibility = if (miniAlpha <= 0f) View.GONE else View.VISIBLE

                    // Fade in full player from 0.25 to 1.0
                    val fullAlpha = ((slideOffset - 0.25f) / 0.75f).coerceIn(0f, 1f)
                    binding.fullPlayerContainer.alpha = fullAlpha
                    binding.fullPlayerContainer.visibility = if (fullAlpha <= 0f) View.GONE else View.VISIBLE
                }
            }
        })
    }

    private fun setupListeners() {
        // Expand player on tapping miniplayer container
        binding.miniplayerContainer.setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        // Collapse player
        binding.btnCollapsePlayer.setOnClickListener {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        // Miniplayer Play/Pause
        binding.btnMiniPlayPause.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPlayPauseAction()
        }

        // Miniplayer Next
        binding.btnMiniNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onNextAction()
        }

        // Full Player Controls
        binding.btnFullPlayPause.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPlayPauseAction()
        }

        binding.btnNext.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onNextAction()
        }

        binding.btnPrevious.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onPreviousAction()
        }

        // Seek Bar Listener
        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && durationSeconds > 0) {
                    val targetSec = (progress / 1000f) * durationSeconds
                    binding.tvCurrentTime.text = formatTime(targetSec)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeek = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeek = false
                val progress = seekBar?.progress ?: 0
                if (durationSeconds > 0) {
                    val targetSec = (progress / 1000f) * durationSeconds
                    onSeekAction(targetSec)
                }
            }
        })
    }

    fun updateTrack(title: String, artist: String, artworkUrl: String) {
        if (title.isBlank() || title == "YouTube Music") return

        binding.tvMiniTitle.text = title
        binding.tvFullTitle.text = title
        binding.tvMiniArtist.text = artist
        binding.tvFullArtist.text = artist

        if (artworkUrl.isNotBlank() && !artworkUrl.startsWith("data:")) {
            binding.ivMiniArtwork.load(artworkUrl) {
                crossfade(true)
            }
            binding.ivFullArtwork.load(artworkUrl) {
                crossfade(true)
            }
        }

        if (behavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun updatePlaybackState(playing: Boolean) {
        isPlaying = playing
        val playIconRes = if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow

        binding.btnMiniPlayPause.setImageResource(playIconRes)
        binding.ivFullPlayPauseIcon.setImageResource(playIconRes)
    }

    fun updateProgress(currentSeconds: Float, totalSeconds: Float) {
        if (isUserTrackingSeek) return

        durationSeconds = totalSeconds
        if (totalSeconds > 0) {
            val progressInt = ((currentSeconds / totalSeconds) * 1000).toInt().coerceIn(0, 1000)
            binding.playerSeekBar.progress = progressInt
            binding.pbMiniProgress.progress = progressInt
            binding.tvCurrentTime.text = formatTime(currentSeconds)
            binding.tvTotalDuration.text = formatTime(totalSeconds)
        } else {
            binding.tvCurrentTime.text = formatTime(currentSeconds)
            binding.tvTotalDuration.text = "0:00"
        }
    }

    val isExpanded: Boolean
        get() = behavior.state == BottomSheetBehavior.STATE_EXPANDED

    fun collapse(): Boolean {
        if (isExpanded) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
            return true
        }
        return false
    }

    private fun formatTime(seconds: Float): String {
        val totalSec = seconds.toInt().coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%d:%02d", m, s)
    }
}
