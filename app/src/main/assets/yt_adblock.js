// Hậu OS - chặn quảng cáo trong video YouTube (học từ repo Y-utubecuatoi).
// (1) Khi trình phát đang chiếu quảng cáo: tắt tiếng và tua thẳng tới cuối đoạn quảng cáo.
// (2) Ẩn các khung quảng cáo "được tài trợ" nổi đè lên video.
// Chặn request tới domain quảng cáo được làm ở tầng mạng (MainActivity + blocklist.txt).
(function () {
  if (window.__hauosAdBlock) return;
  window.__hauosAdBlock = true;

  var OVERLAY_SELECTORS = [
    '.ytp-ad-overlay-image', '.ytp-ad-overlay-container', '.ytp-ad-overlay-slot',
    '.ytp-ad-text-overlay', '.ytp-ad-player-overlay',
    'ytm-companion-slot', 'ytm-companion-ad-renderer'
  ];

  function blockAds() {
    try {
      if (document.hidden) return;   // trang ẩn (đang ở màn hình khác / app ra nền): không dò quảng cáo
      var player = document.querySelector('.html5-video-player');
      var video = document.querySelector('video');
      var isAd = player && /ad-showing|ad-interrupting/.test(player.className || '');

      if (isAd && video) {
        if (!video.muted) {
          video.muted = true;
          video.__hauosMuted = true;
        }
        // Tua tới cuối quảng cáo. Khi duration chưa sẵn sàng thì lần kiểm tra sau sẽ làm tiếp.
        if (video.duration && isFinite(video.duration)) {
          try { video.currentTime = video.duration; } catch (e) {}
        }
      } else if (video && video.__hauosMuted) {
        // Hết quảng cáo: chỉ trả tiếng nếu chính đoạn này đã tắt tiếng.
        video.muted = false;
        video.__hauosMuted = false;
      }

      OVERLAY_SELECTORS.forEach(function (sel) {
        document.querySelectorAll(sel).forEach(function (el) {
          el.style.display = 'none';
        });
      });
    } catch (e) {}
  }

  setInterval(blockAds, 150);

  var root = document.documentElement;
  if (root) {
    new MutationObserver(blockAds).observe(root, { childList: true, subtree: true });
  }
})();
