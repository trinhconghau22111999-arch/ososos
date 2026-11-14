// Hậu OS - luôn phát YouTube nhanh 2x.
// YouTube tự đặt lại tốc độ về 1x mỗi khi đổi video / load lại player, nên script vừa
// theo dõi sự kiện ratechange vừa kiểm tra định kỳ để ép về 2x. Không đụng vào quảng cáo.
(function () {
  if (window.__hauosSpeed) return;
  window.__hauosSpeed = true;
  var RATE = 2;
  function isAd() {
    var p = document.querySelector('.html5-video-player');
    return !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
  }
  function apply(v) {
    if (!v || isAd()) return;
    if (v.defaultPlaybackRate !== RATE) v.defaultPlaybackRate = RATE;
    if (v.playbackRate !== RATE) v.playbackRate = RATE;
  }
  function hook(v) {
    if (v.__hauosSpeedHook) return;
    v.__hauosSpeedHook = true;
    ['loadstart', 'loadedmetadata', 'playing', 'ratechange'].forEach(function (ev) {
      v.addEventListener(ev, function () { apply(v); }, true);
    });
  }
  setInterval(function () {
    try {
      var list = document.querySelectorAll('video');
      for (var i = 0; i < list.length; i++) { hook(list[i]); apply(list[i]); }
    } catch (e) { }
  }, 300);
})();
