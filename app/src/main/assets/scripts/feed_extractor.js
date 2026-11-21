/**
 * Hum Music - Feed Extractor & Native Playback Controller
 * Extracts personalized shelves and recommendations from YouTube Music DOM
 * and sends them across BraveMusicBridge for native Stale-While-Revalidate (SWR) UI.
 */
(function() {
    'use strict';

    if (window.__humFeedExtractorInstalled) return;
    window.__humFeedExtractorInstalled = true;

    window.BraveMusicControls = window.BraveMusicControls || {};

    // Play a track by videoId or navigate to watch endpoint
    window.BraveMusicControls.playTrack = function(videoId, playlistId) {
        if (!videoId) return;
        
        // 1. Try finding an anchor in the active DOM for zero-reload SPA navigation
        const selector = 'a[href*="' + videoId + '"]';
        const link = document.querySelector(selector);
        if (link) {
            link.click();
            return;
        }

        // 2. Direct navigation to watch endpoint
        let targetUrl = '/watch?v=' + encodeURIComponent(videoId);
        if (playlistId) {
            targetUrl += '&list=' + encodeURIComponent(playlistId);
        }
        window.location.href = targetUrl;
    };

    let lastSentFeedHash = '';

    function extractFeed() {
        try {
            const shelves = document.querySelectorAll('ytmusic-carousel-shelf-renderer, ytmusic-section-list-renderer ytmusic-shelf-renderer');
            if (!shelves || shelves.length === 0) return null;

            const sections = [];

            shelves.forEach((shelf, sIdx) => {
                const titleEl = shelf.querySelector('h2, .title, yt-formatted-string.title');
                const title = titleEl ? titleEl.textContent.trim() : ('Mix ' + (sIdx + 1));

                const itemEls = shelf.querySelectorAll('ytmusic-two-row-item-renderer, ytmusic-responsive-list-item-renderer');
                const items = [];

                itemEls.forEach((item, iIdx) => {
                    const data = item.data;
                    let itemTitle = '';
                    let itemSubtitle = '';
                    let thumb = '';
                    let videoId = '';
                    let playlistId = '';

                    if (data) {
                        // Title
                        if (data.title && data.title.runs && data.title.runs[0]) {
                            itemTitle = data.title.runs[0].text;
                        } else if (data.flexColumns && data.flexColumns[0]) {
                            const r = data.flexColumns[0].musicResponsiveListItemFlexColumnRenderer;
                            if (r && r.text && r.text.runs && r.text.runs[0]) itemTitle = r.text.runs[0].text;
                        }

                        // Subtitle
                        if (data.subtitle && data.subtitle.runs) {
                            itemSubtitle = data.subtitle.runs.map(r => r.text).join('');
                        } else if (data.flexColumns && data.flexColumns[1]) {
                            const r = data.flexColumns[1].musicResponsiveListItemFlexColumnRenderer;
                            if (r && r.text && r.text.runs) itemSubtitle = r.text.runs.map(r => r.text).join('');
                        }

                        // Thumbnail
                        if (data.thumbnail && data.thumbnail.musicThumbnailRenderer) {
                            const thumbs = data.thumbnail.musicThumbnailRenderer.thumbnail.thumbnails;
                            if (thumbs && thumbs.length) {
                                thumb = thumbs[thumbs.length - 1].url;
                            }
                        }

                        // Endpoint
                        const ep = data.navigationEndpoint || (data.flexColumns && data.flexColumns[0] && data.flexColumns[0].musicResponsiveListItemFlexColumnRenderer.text.runs[0].navigationEndpoint);
                        if (ep && ep.watchEndpoint) {
                            videoId = ep.watchEndpoint.videoId || '';
                            playlistId = ep.watchEndpoint.playlistId || '';
                        }
                    }

                    // Fallbacks
                    if (!itemTitle) {
                        const tEl = item.querySelector('.title, .primary-text, yt-formatted-string');
                        if (tEl) itemTitle = tEl.textContent.trim();
                    }
                    if (!itemSubtitle) {
                        const sEl = item.querySelector('.subtitle, .secondary-text');
                        if (sEl) itemSubtitle = sEl.textContent.trim();
                    }
                    if (!thumb) {
                        const img = item.querySelector('img');
                        if (img && img.src && !img.src.startsWith('data:')) thumb = img.src;
                    }
                    if (!videoId) {
                        const a = item.querySelector('a');
                        if (a && a.href) {
                            const match = a.href.match(/[?&]v=([^&]+)/);
                            if (match) videoId = match[1];
                        }
                    }

                    if (itemTitle && (videoId || thumb)) {
                        items.push({
                            id: videoId || ('item_' + sIdx + '_' + iIdx),
                            title: itemTitle,
                            subtitle: itemSubtitle,
                            thumbnailUrl: thumb,
                            videoId: videoId,
                            playlistId: playlistId
                        });
                    }
                });

                if (items.length > 0) {
                    sections.push({
                        id: 'section_' + sIdx,
                        title: title,
                        items: items
                    });
                }
            });

            return sections.length > 0 ? sections : null;
        } catch (e) {
            console.error('Feed extraction error:', e);
            return null;
        }
    }

    function checkAndSendFeed() {
        const sections = extractFeed();
        if (!sections) return;

        const jsonStr = JSON.stringify(sections);
        if (jsonStr === lastSentFeedHash) return; // Unchanged

        lastSentFeedHash = jsonStr;
        if (window.BraveMusicBridge && typeof window.BraveMusicBridge.onHomeFeedLoaded === 'function') {
            window.BraveMusicBridge.onHomeFeedLoaded(jsonStr);
        }
    }

    // Check periodically during page lifecycle and on DOM mutations
    let debounceTimer = null;
    const observer = new MutationObserver(() => {
        if (debounceTimer) clearTimeout(debounceTimer);
        debounceTimer = setTimeout(checkAndSendFeed, 1000);
    });

    const browsePage = document.querySelector('ytmusic-browse-response') || document.body;
    if (browsePage) {
        observer.observe(browsePage, { childList: true, subtree: true });
    }

    // Initial checks at staggered intervals to catch fast initial render
    setTimeout(checkAndSendFeed, 1000);
    setTimeout(checkAndSendFeed, 2500);
    setTimeout(checkAndSendFeed, 5000);
})();
