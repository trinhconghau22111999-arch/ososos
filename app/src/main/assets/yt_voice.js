// Hậu OS - nút micro "Tìm kiếm bằng giọng nói" của YouTube (học từ repo Y-utubecuatoi).
// WebView không chạy được Web Speech API, nên bắt cú bấm và chuyển sang nhận dạng giọng nói
// gốc của Android qua cầu nối HauOsVoice (xem MainActivity.startYtVoice).
// Dò theo aria-label/title vì nhãn hỗ trợ tiếp cận ít khi đổi giữa các bản cập nhật của YouTube.
(function () {
  if (window.__hauosVoice) return;
  window.__hauosVoice = true;

  var VOICE_LABELS = [
    'tìm kiếm bằng giọng nói', 'tim kiem bang giong noi',
    'search by voice', 'voice search', 'search with your voice'
  ];

  function isVoiceButton(el) {
    var t = ((el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '')).toLowerCase();
    for (var i = 0; i < VOICE_LABELS.length; i++) {
      if (t.indexOf(VOICE_LABELS[i]) !== -1) return true;
    }
    return false;
  }

  function hookVoiceButtons() {
    if (document.hidden) return;
    document.querySelectorAll('button, [role="button"], a').forEach(function (el) {
      if (el.__hauosVoiceBound || !isVoiceButton(el)) return;
      el.__hauosVoiceBound = true;
      el.addEventListener('click', function (ev) {
        if (!window.HauOsVoice) return;
        ev.preventDefault();
        ev.stopPropagation();
        window.HauOsVoice.startVoiceSearch();
      }, true);
    });
  }

  hookVoiceButtons();
  setInterval(hookVoiceButtons, 1000);

  var root = document.documentElement;
  if (root) {
    new MutationObserver(hookVoiceButtons).observe(root, { childList: true, subtree: true });
  }
})();
