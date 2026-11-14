// Hậu OS - bấm vào logo/tiêu đề "YouTube" ở thanh trên sẽ mở trang "Xem sau" (danh sách WL).
// Dùng 1 listener ở tầng document (pha capture) nên vẫn chạy khi YouTube vẽ lại thanh trên.
(function () {
  if (window.__hauosWatchLater) return;
  window.__hauosWatchLater = true;

  var WL_URL = 'https://m.youtube.com/playlist?list=WL';
  var LOGO_SEL = 'ytm-home-logo, .mobile-topbar-logo, a[aria-label="YouTube Home"], a[aria-label="Trang chủ YouTube"], a[href="/"]';
  var BAR_SEL = 'ytm-mobile-topbar-renderer, header.mobile-topbar-header, .mobile-topbar-header';

  function isLogoClick(t) {
    if (!t || !t.closest) return false;
    if (!t.closest(BAR_SEL)) return false;      // chỉ logo trên thanh trên, không đụng link khác
    return !!t.closest(LOGO_SEL);
  }

  document.addEventListener('click', function (ev) {
    if (!isLogoClick(ev.target)) return;
    ev.preventDefault();
    ev.stopPropagation();
    ev.stopImmediatePropagation();
    if (location.pathname === '/playlist' && location.search.indexOf('list=WL') !== -1) return;
    location.href = WL_URL;
  }, true);
})();
