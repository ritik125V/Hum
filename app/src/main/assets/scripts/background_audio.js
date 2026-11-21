/**
 * Ultra Low-RAM Background Audio & Full MediaSession / YouTube Music Native Sync
 * - Runs with ZERO polling intervals and ZERO whole-document MutationObservers
 * - Polyfills navigator.mediaSession & MediaMetadata for Android WebView
 * - Real-time sync with movie_player.getVideoData() & ytmusic-player-bar DOM
 * - Upgrades artwork to crystal-clear 544x544 resolution
 * - Exposes robust play, pause, next, previous, and collapse controls to native Android
 * - Uses native HTML5 <video> events for playback state synchronization
 * - Spoofs Page Visibility API to prevent Chromium from pausing background audio
 * - Leaves touch, blur, and focus events intact to preserve 100% fluid gesture response
 */
(function() {
    'use strict';

    if (window.__brave_bg_audio_injected) {
        return;
    }
    window.__brave_bg_audio_injected = true;

    // 0. Audio-Only Mode State (Suppress music videos & display album artwork)
    let isAudioOnly = true;
    if (window.BraveMusicBridge && typeof window.BraveMusicBridge.isAudioOnly === 'function') {
        try {
            isAudioOnly = window.BraveMusicBridge.isAudioOnly();
        } catch (e) {}
    }

    function applyAudioOnly(enabled) {
        isAudioOnly = !!enabled;
        const html = document.documentElement;
        const body = document.body;

        if (isAudioOnly) {
            if (html) html.classList.add('audio-only-mode');
            if (body) body.classList.add('audio-only-mode');

            // Switch to official Song mode if YouTube AV switcher is available
            try {
                const songToggle = document.querySelector(
                    'ytmusic-av-switcher [aria-label*="Song" i], #song-video-toggle .song-button, ytmusic-av-switcher tp-yt-paper-tab:first-child'
                );
                if (songToggle) {
                    const isSelected = songToggle.getAttribute('aria-selected') === 'true' ||
                                       songToggle.classList.contains('iron-selected');
                    if (!isSelected) {
                        songToggle.click();
                    }
                }
            } catch (e) {}

            // Instruct DASH player to stream 144p ('tiny') to reduce video bandwidth by 95%
            try {
                const mp = document.getElementById('movie_player');
                if (mp) {
                    if (typeof mp.setPlaybackQualityRange === 'function') {
                        mp.setPlaybackQualityRange('tiny', 'tiny');
                    }
                    if (typeof mp.setPlaybackQuality === 'function') {
                        mp.setPlaybackQuality('tiny');
                    }
                }
            } catch (e) {}

            // Ensure #song-image img has artwork if empty
            try {
                const songImage = document.getElementById('song-image');
                if (songImage) {
                    let img = songImage.querySelector('img#img') || songImage.querySelector('img');
                    if (img && (!img.src || img.src.startsWith('data:') || img.src === window.location.href)) {
                        let artwork = extractBestArtwork(null);
                        if (!artwork) {
                            const mp = document.getElementById('movie_player');
                            const vd = (mp && typeof mp.getVideoData === 'function') ? mp.getVideoData() : null;
                            if (vd && vd.video_id) {
                                artwork = 'https://i.ytimg.com/vi/' + vd.video_id + '/hqdefault.jpg';
                            }
                        }
                        if (artwork) {
                            img.src = artwork;
                        }
                    }
                }
            } catch (e) {}
        } else {
            if (html) html.classList.remove('audio-only-mode');
            if (body) body.classList.remove('audio-only-mode');

            // Restore normal playback quality
            try {
                const mp = document.getElementById('movie_player');
                if (mp) {
                    if (typeof mp.setPlaybackQualityRange === 'function') {
                        mp.setPlaybackQualityRange('auto', 'auto');
                    }
                    if (typeof mp.setPlaybackQuality === 'function') {
                        mp.setPlaybackQuality('auto');
                    }
                }
            } catch (e) {}
        }
    }

    // 1. Spoof Page Visibility API (Keep audio playing when app is minimized or screen is locked)
    try {
        if (typeof Document !== 'undefined' && Document.prototype) {
            Object.defineProperty(Document.prototype, 'hidden', {
                get: function() { return false; },
                configurable: true
            });
            Object.defineProperty(Document.prototype, 'visibilityState', {
                get: function() { return 'visible'; },
                configurable: true
            });
        }

        Object.defineProperty(document, 'hidden', {
            get: function() { return false; },
            configurable: true
        });

        Object.defineProperty(document, 'visibilityState', {
            get: function() { return 'visible'; },
            configurable: true
        });

        document.hasFocus = function() { return true; };

        const blockEvents = ['visibilitychange', 'webkitvisibilitychange', 'pagehide'];
        blockEvents.forEach(function(evt) {
            window.addEventListener(evt, function(e) {
                e.stopImmediatePropagation();
            }, true);
            document.addEventListener(evt, function(e) {
                e.stopImmediatePropagation();
            }, true);
        });
    } catch (e) {}

    // 2. Helper: Extract highest-resolution artwork URL (Filtering out 1x1 data: GIFs)
    function extractBestArtwork(artworkArray) {
        let bestUrl = '';
        if (Array.isArray(artworkArray) && artworkArray.length > 0) {
            for (let i = artworkArray.length - 1; i >= 0; i--) {
                const src = artworkArray[i] && artworkArray[i].src;
                if (src && typeof src === 'string' && src.startsWith('http')) {
                    bestUrl = src;
                    break;
                }
            }
        }

        // DOM Fallback if metadata artwork is missing
        if (!bestUrl) {
            const domImgs = Array.from(document.querySelectorAll(
                'ytmusic-player-bar img, ytmusic-player-page img, #player-page img, .thumbnail img'
            ));
            for (let i = 0; i < domImgs.length; i++) {
                const src = domImgs[i].src;
                if (src && typeof src === 'string' && src.startsWith('http')) {
                    bestUrl = src;
                    break;
                }
            }
        }

        // Upgrade Google/YouTube user content thumbnails to high resolution (544x544)
        if (bestUrl) {
            if (bestUrl.includes('=w') && bestUrl.includes('-h')) {
                bestUrl = bestUrl.replace(/=w\d+-h\d+[^=]*/, '=w544-h544-l90-rj');
            } else if (bestUrl.includes('i.ytimg.com/vi/') && bestUrl.includes('/default.jpg')) {
                bestUrl = bestUrl.replace('/default.jpg', '/hqdefault.jpg');
            }
        }

        return bestUrl;
    }

    // Helper: Push metadata to Android Bridge
    function notifyTrackChanged(title, artist, album, artworkUrl) {
        if (window.BraveMusicBridge && typeof window.BraveMusicBridge.onTrackChanged === 'function') {
            window.BraveMusicBridge.onTrackChanged(
                title || 'Playing Music',
                artist || 'YouTube Music',
                album || 'YouTube Music',
                artworkUrl || ''
            );
        }
    }

    // 3. MediaSession & MediaMetadata Polyfill for Android WebView
    const mediaActionHandlers = {};
    let currentMetadata = null;
    let currentPlaybackState = 'none';

    if (typeof window.MediaMetadata === 'undefined') {
        window.MediaMetadata = function(init) {
            init = init || {};
            this.title = init.title || '';
            this.artist = init.artist || '';
            this.album = init.album || '';
            this.artwork = init.artwork || [];
        };
    }

    const polyfilledMediaSession = {
        get metadata() {
            return currentMetadata;
        },
        set metadata(meta) {
            currentMetadata = meta;
            if (meta) {
                const artwork = extractBestArtwork(meta.artwork);
                notifyTrackChanged(
                    meta.title || '',
                    meta.artist || '',
                    meta.album || 'YouTube Music',
                    artwork
                );
            }
        },
        get playbackState() {
            return currentPlaybackState;
        },
        set playbackState(val) {
            currentPlaybackState = val;
            if (window.BraveMusicBridge && typeof window.BraveMusicBridge.onPlaybackState === 'function') {
                window.BraveMusicBridge.onPlaybackState(val === 'playing');
            }
        },
        setActionHandler: function(action, handler) {
            if (action) {
                mediaActionHandlers[action] = handler;
            }
        },
        setPositionState: function() {},
        _getHandlers: function() {
            return mediaActionHandlers;
        }
    };

    try {
        if (!navigator.mediaSession) {
            Object.defineProperty(navigator, 'mediaSession', {
                value: polyfilledMediaSession,
                configurable: true,
                writable: false
            });
        } else {
            // Native MediaSession present: intercept metadata & action handlers
            const origSetActionHandler = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
            navigator.mediaSession.setActionHandler = function(action, handler) {
                if (action) {
                    mediaActionHandlers[action] = handler;
                }
                try {
                    return origSetActionHandler(action, handler);
                } catch (err) {}
            };

            Object.defineProperty(navigator.mediaSession, 'metadata', {
                get: function() {
                    return currentMetadata;
                },
                set: function(meta) {
                    currentMetadata = meta;
                    if (meta) {
                        const artwork = extractBestArtwork(meta.artwork);
                        notifyTrackChanged(
                            meta.title || '',
                            meta.artist || '',
                            meta.album || 'YouTube Music',
                            artwork
                        );
                    }
                },
                configurable: true
            });
        }
    } catch (e) {}

    // 4. Continuous Direct Player Synchronizer (movie_player + DOM)
    let lastReportedTrackKey = '';

    function syncTrackInfoFromPlayer() {
        try {
            const mp = document.getElementById('movie_player');
            const vd = (mp && typeof mp.getVideoData === 'function') ? mp.getVideoData() : null;
            const pb = document.querySelector('ytmusic-player-bar');

            let title = '';
            let artist = '';
            let artwork = '';

            if (pb) {
                title = pb.querySelector('.title')?.textContent?.trim() || '';
                artist = pb.querySelector('.byline')?.textContent?.trim() || '';
            }

            if (!title && vd) {
                title = vd.title || '';
            }
            if (!artist && vd) {
                artist = vd.author || '';
            }

            // Exclude empty or raw generic site titles
            if (!title || title === 'YouTube Music') {
                return;
            }

            // Extract real artwork
            artwork = extractBestArtwork(null);
            if (!artwork && vd && vd.video_id) {
                artwork = 'https://i.ytimg.com/vi/' + vd.video_id + '/hqdefault.jpg';
            }

            const trackKey = title + '||' + artist + '||' + artwork;
            if (trackKey !== lastReportedTrackKey) {
                lastReportedTrackKey = trackKey;
                notifyTrackChanged(title, artist || 'YouTube Music', 'YouTube Music', artwork);
                if (isAudioOnly) {
                    applyAudioOnly(true);
                }
            }
        } catch (err) {}
    }

    // 5. Native Video Event Hook & Playback State Sync
    function hookVideo(video) {
        if (!video || video.__brave_hooked) return;
        video.__brave_hooked = true;

        const reportState = function(isPlaying) {
            if (window.BraveMusicBridge && typeof window.BraveMusicBridge.onPlaybackState === 'function') {
                window.BraveMusicBridge.onPlaybackState(isPlaying);
            }
            syncTrackInfoFromPlayer();
            // Retry sync 400ms later to capture DOM updates
            setTimeout(syncTrackInfoFromPlayer, 400);
            setTimeout(syncTrackInfoFromPlayer, 1200);
        };

        video.addEventListener('play', function() {
            reportState(true);
            if (isAudioOnly) applyAudioOnly(true);
        });
        video.addEventListener('playing', function() {
            reportState(true);
            if (isAudioOnly) applyAudioOnly(true);
        });
        video.addEventListener('pause', function() { reportState(false); });
        video.addEventListener('ended', function() { reportState(false); });
        video.addEventListener('loadedmetadata', function() {
            syncTrackInfoFromPlayer();
            if (isAudioOnly) applyAudioOnly(true);
        });
    }

    const initialVideo = document.querySelector('video');
    if (initialVideo) {
        hookVideo(initialVideo);
    }
    document.addEventListener('play', function(e) {
        if (e.target && e.target.tagName === 'VIDEO') {
            hookVideo(e.target);
        }
    }, true);

    // Targeted DOM observer on mini-player bar text for instant title change detection
    function observeMiniPlayerBar() {
        const pb = document.querySelector('ytmusic-player-bar');
        if (!pb) {
            setTimeout(observeMiniPlayerBar, 500);
            return;
        }
        if (pb.__brave_sync_obs) return;
        pb.__brave_sync_obs = true;

        const obs = new MutationObserver(function() {
            syncTrackInfoFromPlayer();
        });
        obs.observe(pb, { childList: true, subtree: true, characterData: true });
    }
    observeMiniPlayerBar();

    // 6. Ensure Android Soft Keyboard (IME) opens cleanly on search/input focus
    document.addEventListener('focusin', function(e) {
        const target = e.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
            if (window.BraveMusicBridge && typeof window.BraveMusicBridge.showKeyboard === 'function') {
                window.BraveMusicBridge.showKeyboard();
            }
        }
    }, true);

    document.addEventListener('focusout', function(e) {
        const target = e.target;
        if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
            if (window.BraveMusicBridge && typeof window.BraveMusicBridge.hideKeyboard === 'function') {
                window.BraveMusicBridge.hideKeyboard();
            }
        }
    }, true);

    // 7. Targeted Player State Observer on #player-page only
    function observePlayerState() {
        const playerPage = document.getElementById('player-page');
        if (!playerPage) {
            setTimeout(observePlayerState, 500);
            return;
        }
        if (playerPage.__brave_state_observer) return;
        playerPage.__brave_state_observer = true;

        const updateState = () => {
            const state = playerPage.getAttribute('player-page-ui-state');
            const isFull = state === 'FULL_PLAYER_VIEW';
            if (window.BraveMusicBridge && typeof window.BraveMusicBridge.onPlayerState === 'function') {
                window.BraveMusicBridge.onPlayerState(isFull);
            }
            syncTrackInfoFromPlayer();
        };

        const observer = new MutationObserver(updateState);
        observer.observe(playerPage, { attributes: true, attributeFilter: ['player-page-ui-state'] });
        updateState();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', observePlayerState);
    } else {
        observePlayerState();
    }

    // 8. Robust Playback & Navigation Controls Exposed to Native Android
    window.BraveMusicControls = {
        play: function() {
            if (typeof mediaActionHandlers['play'] === 'function') {
                try { mediaActionHandlers['play'](); return; } catch (e) {}
            }
            const mp = document.getElementById('movie_player');
            if (mp && typeof mp.playVideo === 'function') {
                try { mp.playVideo(); return; } catch (e) {}
            }
            const video = document.querySelector('video');
            if (video) video.play();
        },
        pause: function() {
            if (typeof mediaActionHandlers['pause'] === 'function') {
                try { mediaActionHandlers['pause'](); return; } catch (e) {}
            }
            const mp = document.getElementById('movie_player');
            if (mp && typeof mp.pauseVideo === 'function') {
                try { mp.pauseVideo(); return; } catch (e) {}
            }
            const video = document.querySelector('video');
            if (video) video.pause();
        },
        next: function() {
            if (typeof mediaActionHandlers['nexttrack'] === 'function') {
                try { mediaActionHandlers['nexttrack'](); return; } catch (e) {}
            }
            const mp = document.getElementById('movie_player');
            if (mp && typeof mp.nextVideo === 'function') {
                try { mp.nextVideo(); return; } catch (e) {}
            }
            const nextBtn = document.querySelector(
                'button[aria-label="Next"], button[aria-label="next"], ytmusic-player-bar .next-button button, ytmusic-player-page .next-button button'
            );
            if (nextBtn) nextBtn.click();
        },
        previous: function() {
            if (typeof mediaActionHandlers['previoustrack'] === 'function') {
                try { mediaActionHandlers['previoustrack'](); return; } catch (e) {}
            }
            const mp = document.getElementById('movie_player');
            if (mp && typeof mp.previousVideo === 'function') {
                try { mp.previousVideo(); return; } catch (e) {}
            }
            const prevBtn = document.querySelector(
                'button[aria-label="Previous"], button[aria-label="previous"], ytmusic-player-page .previous-button button'
            );
            if (prevBtn) {
                prevBtn.click();
                return;
            }
            const video = document.querySelector('video');
            if (video) video.currentTime = 0;
        },
        seekTo: function(seconds) {
            if (typeof mediaActionHandlers['seekto'] === 'function') {
                try { mediaActionHandlers['seekto']({ seekTime: seconds }); return; } catch (e) {}
            }
            const mp = document.getElementById('movie_player');
            if (mp && typeof mp.seekTo === 'function') {
                try { mp.seekTo(seconds, true); return; } catch (e) {}
            }
            const video = document.querySelector('video');
            if (video) video.currentTime = seconds;
        },
        isPlayerExpanded: function() {
            const pp = document.getElementById('player-page');
            return pp ? pp.getAttribute('player-page-ui-state') === 'FULL_PLAYER_VIEW' : false;
        },
        collapsePlayer: function() {
            const pp = document.getElementById('player-page');
            const isFull = pp && pp.getAttribute('player-page-ui-state') === 'FULL_PLAYER_VIEW';
            if (isFull) {
                const btn = document.querySelector('#collapse-button button') ||
                            document.querySelector('#collapse-button') ||
                            document.querySelector('[aria-label*="Minim" i], [aria-label*="Close player" i]');
                if (btn) {
                    const target = btn.querySelector('button') || btn;
                    target.click();
                    return true;
                }
            }
            return false;
        },
        goHome: function() {
            if (window.BraveMusicControls.isPlayerExpanded()) {
                window.BraveMusicControls.collapsePlayer();
            }
            const homeBtn = document.querySelector('ytmusic-nav-bar a.logo, ytmusic-logo a, a[href="/"], [aria-label*="Home" i]');
            if (homeBtn) {
                const target = homeBtn.querySelector('a, button') || homeBtn;
                target.click();
                return true;
            }
            window.location.href = 'https://music.youtube.com/';
            return true;
        },
        setAudioOnly: function(enabled) {
            applyAudioOnly(enabled);
        },
        isAudioOnly: function() {
            return isAudioOnly;
        }
    };

    // Run initial Audio-Only configuration
    applyAudioOnly(isAudioOnly);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            applyAudioOnly(isAudioOnly);
        });
    }
})();
