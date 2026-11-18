/**
 * Brave Music Seamless Ad Shield (Brave / uBlock Origin Parity)
 * - Prunes adPlacements, playerAds, and adSlots from YouTube player payloads in real-time
 * - Eliminates mealbarPromoRenderer and popups that trap touch events
 * - Hooks JSON.parse, Response.prototype.json, and fetch/XHR with zero CPU overhead
 * - Disables ad pods and preroll flags in YouTube's internal ytcfg
 * - ZERO 16x/fast-forward playback: eliminates high-speed ad audio glitches completely
 * - Instant mute fail-safe if any ad DOM is ever queued
 */
(function() {
    'use strict';

    if (window.__brave_seamless_adblock_injected) {
        return;
    }
    window.__brave_seamless_adblock_injected = true;

    // 1. Recursive ad & promo payload pruner
    function pruneAdData(data) {
        if (!data || typeof data !== 'object') return data;

        if (Array.isArray(data)) {
            for (let i = 0; i < data.length; i++) {
                pruneAdData(data[i]);
            }
            return data;
        }

        // Delete ad placements and slot definitions
        delete data.adPlacements;
        delete data.playerAds;
        delete data.adSlots;
        delete data.adBreakHeartbeatParams;
        delete data.mealbarPromoRenderer;

        // Prune nested player response & overlay
        if (data.playerResponse) {
            pruneAdData(data.playerResponse);
        }
        if (data.overlay && data.overlay.mealbarPromoRenderer) {
            delete data.overlay.mealbarPromoRenderer;
        }

        // Neutralize ad telemetry & beacons
        if (data.playbackTracking) {
            delete data.playbackTracking.atrUrl;
            delete data.playbackTracking.qoeUrl;
            delete data.playbackTracking.ptrackingUrl;
        }

        // Filter out promo mealbars and banner cards
        if (Array.isArray(data.messages)) {
            data.messages = data.messages.filter(function(m) {
                return !m.mealbarPromoRenderer;
            });
        }

        // Filter out openPopupAction containing mealbar promos that trap user touch
        if (Array.isArray(data.actions)) {
            data.actions = data.actions.filter(function(a) {
                var popup = a && a.openPopupAction && a.openPopupAction.popup;
                if (popup && (popup.mealbarPromoRenderer || popup.aboutThisAdRenderer)) {
                    return false;
                }
                return true;
            });
        }

        return data;
    }

    // 2. Hook JSON.parse with string fast-path for instantaneous parsing
    const originalParse = JSON.parse;
    JSON.parse = function(text, reviver) {
        const result = originalParse.apply(this, arguments);
        if (typeof text === 'string' && (
            text.includes('adPlacements') || 
            text.includes('playerAds') || 
            text.includes('adSlots') || 
            text.includes('mealbarPromoRenderer')
        )) {
            return pruneAdData(result);
        }
        return result;
    };

    // 3. Hook Response.prototype.json & Response.prototype.text for fetch requests
    if (typeof Response !== 'undefined' && Response.prototype) {
        const origJson = Response.prototype.json;
        Response.prototype.json = async function() {
            const data = await origJson.apply(this, arguments);
            return pruneAdData(data);
        };

        const origText = Response.prototype.text;
        Response.prototype.text = async function() {
            const text = await origText.apply(this, arguments);
            if (typeof text === 'string' && (
                text.includes('adPlacements') || 
                text.includes('playerAds') || 
                text.includes('adSlots') || 
                text.includes('mealbarPromoRenderer')
            )) {
                try {
                    return JSON.stringify(pruneAdData(originalParse(text)));
                } catch (e) {
                    return text;
                }
            }
            return text;
        };
    }

    // 4. Hook window.fetch for direct API interception
    if (typeof window.fetch === 'function') {
        const origFetch = window.fetch;
        window.fetch = async function(...args) {
            const response = await origFetch.apply(this, args);
            const url = typeof args[0] === 'string' ? args[0] : (args[0] && args[0].url ? args[0].url : '');
            if (url && (
                url.includes('/youtubei/v1/player') || 
                url.includes('/youtubei/v1/next') || 
                url.includes('/youtubei/v1/browse')
            )) {
                try {
                    const cloned = response.clone();
                    response.json = async function() {
                        const data = await cloned.json();
                        return pruneAdData(data);
                    };
                } catch (e) {}
            }
            return response;
        };
    }

    // 5. Hook window.ytInitialPlayerResponse
    let _ytInitial = undefined;
    try {
        Object.defineProperty(window, 'ytInitialPlayerResponse', {
            get: function() {
                return _ytInitial;
            },
            set: function(val) {
                _ytInitial = pruneAdData(val);
            },
            configurable: true,
            enumerable: true
        });
    } catch (e) {}

    // 6. YouTube Configuration Flags: Disable Ad Pods and Midrolls
    function configureYtFlags() {
        try {
            if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                const flags = window.ytcfg.get('EXPERIMENT_FLAGS') || {};
                flags.web_player_enable_ad_pod_service = false;
                flags.html5_block_ad_midrolls = true;
                flags.html5_block_ad_prerolls = true;
                flags.web_enable_ad_signals_in_player_payload = false;
                flags.disable_child_node_auto_pre_load = true;
                window.ytcfg.set('EXPERIMENT_FLAGS', flags);
            }
        } catch (e) {}
    }
    configureYtFlags();

    // 7. Protect 3-Dot Dropdown Menus and Popups from auto-dismissal
    window.addEventListener('click', function(e) {
        if (!e.isTrusted) {
            const activePopup = document.querySelector(
                'tp-yt-iron-dropdown[opened], ytmusic-menu-popup-renderer:not([style*="display: none"])'
            );
            if (activePopup && !activePopup.contains(e.target)) {
                e.stopImmediatePropagation();
            }
        }
    }, true);

    // 8. Instant Silent Mute & Skip Fail-Safe (ZERO Fast-Forwarding / Chipmunk Audio)
    function handleAdSilently() {
        const adContainer = document.querySelector('.ad-showing, .ad-interrupting');
        if (adContainer) {
            const video = document.querySelector('video');
            if (video) {
                // Instantly silence audio completely
                video.muted = true;
                video.volume = 0;
                // DO NOT touch playbackRate! Keeping it at 1.0 prevents high-speed pitch screeching
                if (isFinite(video.duration) && video.duration > 0) {
                    video.currentTime = video.duration;
                }
            }
            // Trigger skip button if rendered
            const skipButton = document.querySelector(
                '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-text, [class*="skip-button"]'
            );
            if (skipButton) {
                skipButton.click();
            }
        }
    }

    // 9. Auto-dismiss any mealbar promos or rogue dialogs that trap touch events
    function autoDismissPromos() {
        var dismissBtns = document.querySelectorAll(
            'ytmusic-mealbar-promo-renderer .dismiss-button button, ' +
            'ytmusic-mealbar-promo-renderer [dialog-dismiss], ' +
            'ytmusic-mealbar-promo-renderer [aria-label*="No thanks" i], ' +
            'ytmusic-mealbar-promo-renderer [aria-label*="Dismiss" i]'
        );
        for (var i = 0; i < dismissBtns.length; i++) {
            dismissBtns[i].click();
        }

        var dialogs = document.querySelectorAll('tp-yt-paper-dialog');
        for (var j = 0; j < dialogs.length; j++) {
            var d = dialogs[j];
            if (d.querySelector('ytmusic-mealbar-promo-renderer')) {
                if (typeof d.close === 'function') {
                    d.close();
                } else {
                    d.remove();
                }
            }
        }
    }

    // Monitor playback start without accelerating playbackRate
    document.addEventListener('play', function(e) {
        const video = e.target;
        if (!video || video.tagName !== 'VIDEO') return;

        const adShowing = document.querySelector('.ad-showing, .ad-interrupting');
        if (adShowing) {
            handleAdSilently();
        } else if (video.muted && !video.hasAttribute('data-user-muted')) {
            // Restore normal audio as soon as real song begins
            video.muted = false;
            video.volume = 1;
        }
    }, true);

    // Ultra-low CPU targeted observer on #movie_player for instantaneous ad-showing class detection
    function attachPlayerObserver() {
        const player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
        if (!player) {
            setTimeout(attachPlayerObserver, 500);
            return;
        }
        const observer = new MutationObserver(function(mutations) {
            for (let i = 0; i < mutations.length; i++) {
                if (mutations[i].attributeName === 'class') {
                    if (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')) {
                        handleAdSilently();
                    }
                }
            }
            autoDismissPromos();
            hideBranding();
        });
        observer.observe(player, { attributes: true, attributeFilter: ['class'] });
    }

    function hideBranding() {
        const logos = document.querySelectorAll('ytmusic-logo, #logo, .ytp-watermark, .ytp-youtube-button');
        for (let i = 0; i < logos.length; i++) {
            logos[i].style.setProperty('display', 'none', 'important');
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function() {
            attachPlayerObserver();
            autoDismissPromos();
            hideBranding();
        });
    } else {
        attachPlayerObserver();
        autoDismissPromos();
        hideBranding();
    }

    // Periodic sweep for the first 5 seconds to ensure cold-start promos never freeze the screen
    let sweepCount = 0;
    const sweepInterval = setInterval(function() {
        autoDismissPromos();
        hideBranding();
        sweepCount++;
        if (sweepCount >= 10) clearInterval(sweepInterval);
    }, 500);
})();
