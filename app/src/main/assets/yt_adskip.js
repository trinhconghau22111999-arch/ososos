// Hậu OS - tự bấm "Bỏ qua quảng cáo" trên YouTube.
// Học từ repo Qrtuxa (desktop/webview-preload.js): tìm nút bỏ qua đang HIỆN THẬT,
// chờ nó hiện >= 500ms rồi báo toạ độ về app; app sẽ gửi cú chạm THẬT vào toạ độ đó
// (YouTube bỏ qua click giả bằng btn.click()). Mỗi lần báo cách nhau >= 2 giây.
(function () {
  if (window.__hauosAdSkip) return;
  window.__hauosAdSkip = true;
  var SELECTORS = ['.ytp-ad-skip-button', '.ytp-ad-skip-button-modern', '.ytp-skip-ad-button',
    '.videoAdUiSkipButton', '.ytp-ad-skip-button-container button'];
  var shownSince = new WeakMap();
  var lastSent = 0;
  function hiddenStyle(cs) {
    return cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.1;
  }
  function reallyVisible(el) {
    if (!el || !el.isConnected) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 8 || r.height < 8) return false;
    if (r.bottom <= 0 || r.right <= 0 || r.left >= window.innerWidth || r.top >= window.innerHeight) return false;
    if (hiddenStyle(getComputedStyle(el))) return false;
    for (var p = el.parentElement; p && p !== document.body; p = p.parentElement) {
      if (hiddenStyle(getComputedStyle(p))) return false;
    }
    return !el.disabled && el.getAttribute('aria-disabled') !== 'true';
  }
  setInterval(function () {
    try {
      if (document.visibilityState !== 'visible' || !window.HauOsAd) return;
      var now = Date.now();
      for (var i = 0; i < SELECTORS.length; i++) {
        var list = document.querySelectorAll(SELECTORS[i]);
        for (var j = 0; j < list.length; j++) {
          var btn = list[j];
          if (!reallyVisible(btn)) { shownSince.delete(btn); continue; }
          var t0 = shownSince.get(btn);
          if (!t0) { shownSince.set(btn, now); continue; }
          if (now - t0 < 500 || now - lastSent < 2000) continue;
          var r = btn.getBoundingClientRect();
          // Điểm bấm ngẫu nhiên trong vùng giữa nút
          var cx = r.left + r.width * (0.35 + Math.random() * 0.3);
          var cy = r.top + r.height * (0.35 + Math.random() * 0.3);
          lastSent = now;
          shownSince.delete(btn);
          window.HauOsAd.skip(cx, cy, window.devicePixelRatio || 1);
          return;
        }
      }
    } catch (e) { }
  }, 250);
})();
