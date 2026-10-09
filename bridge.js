(function () {
  if (window.__lhNative) return;
  window.__lhNative = true;

  function show(title, body) {
    try { window.AndroidNotify.show(String(title || ''), String(body || '')); } catch (e) {}
  }

  // 1) Notification polyfill. The chart scanner calls new Notification(...).
  function N(title, opts) {
    opts = opts || {};
    show(title, opts.body);
    this.close = function () {};
  }
  N.permission = 'granted';
  N.requestPermission = function (cb) {
    if (cb) cb('granted');
    return Promise.resolve('granted');
  };
  try {
    Object.defineProperty(window, 'Notification', { value: N, configurable: true, writable: true });
  } catch (e) {
    window.Notification = N;
  }

  // 2) Signal watcher. Reads the app's own EaSignal responses and notifies on NEW ones.
  var EV = { open: 1, close: 1, modify: 1, pending: 1 };
  var seen = {};
  var maxTs = 0;
  var ready = false;

  function up(x) { return String(x == null ? '' : x).toUpperCase(); }
  function pos(x) { var n = Number(x); return isFinite(n) && n > 0 ? n : null; }

  function ts(e) {
    var s = e.created_date;
    if (!s) return 0;
    s = String(s);
    if (!/[zZ]$|[+-]\d\d:?\d\d$/.test(s)) s += 'Z';
    var t = Date.parse(s);
    return isNaN(t) ? 0 : t;
  }

  function build(e) {
    var sym = up(e.symbol);
    var dir = e.direction && e.direction !== 'none' ? up(e.direction) : '';
    var parts = [];
    if (pos(e.lot)) parts.push(pos(e.lot) + ' lot');
    if (pos(e.price)) parts.push('@ ' + e.price);
    if (pos(e.sl)) parts.push('SL ' + e.sl);
    if (pos(e.tp)) parts.push('TP ' + e.tp);
    var extra = parts.join(' \u00b7 ');
    var t, b;
    if (e.event === 'open') {
      t = (dir ? dir + ' ' : '') + sym;
      b = 'Trade opened' + (extra ? ' \u00b7 ' + extra : '');
    } else if (e.event === 'pending') {
      t = 'PENDING ' + (dir ? dir + ' ' : '') + sym;
      b = 'Entry order' + (extra ? ' \u00b7 ' + extra : '');
    } else if (e.event === 'modify') {
      t = sym + ' UPDATED';
      b = 'SL/TP changed' + (extra ? ' \u00b7 ' + extra : '');
    } else {
      t = sym + ' CLOSED';
      b = typeof e.profit === 'number' ? 'P/L ' + e.profit : 'Trade closed';
      if (extra) b += ' \u00b7 ' + extra;
    }
    if (e.message && e.account === 'manual') b = String(e.message);
    return [t, b];
  }

  function handle(list, isEventQuery) {
    var items = [], i, e;
    for (i = 0; i < list.length; i++) {
      e = list[i];
      if (e && e.id && EV[e.event]) items.push(e);
    }
    items.sort(function (a, b) { return ts(a) - ts(b); });
    var now = Date.now();
    for (i = 0; i < items.length; i++) {
      e = items[i];
      if (seen[e.id]) continue;
      seen[e.id] = 1;
      var t = ts(e);
      var fresh = ready && t >= maxTs && (!t || now - t < 900000);
      if (t > maxTs) maxTs = t;
      if (fresh) {
        var m = build(e);
        show(m[0], m[1]);
      }
    }
    if (isEventQuery) ready = true;
  }

  function isEventUrl(url) {
    try { url = decodeURIComponent(url); } catch (e) {}
    return /open|close|modify|pending/.test(url) && !/heartbeat/.test(url);
  }

  function parse(data, url) {
    try {
      if (typeof data === 'string') data = JSON.parse(data);
      var list = Array.isArray(data) ? data :
        (data && (data.items || data.data || data.results || data.records || data.entities));
      handle(Array.isArray(list) ? list : [], isEventUrl(url));
    } catch (e) {}
  }

  // 4) Skin the web bubble with the app logo, so the page bubble matches the app icon.
  //    Only the background is replaced; the page's own click handler and status dot stay.
  // Robot logo (same artwork as the app icon), embedded so it works offline.
  // We only change the picture inside the page's own bubble <img>. The bubble's
  // CSS, dot and click handling are left exactly as the web app draws them.
  var LOGO_URL = 'data:image/webp;base64,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';
  function swapLogo() {
    try {
      var im = document.querySelector('.float-bubble > img');
      if (im && im.getAttribute('src') !== LOGO_URL) im.setAttribute('src', LOGO_URL);
    } catch (e) {}
  }

  // 3) Bot ON/OFF = the web app's own bubble.
  // The page only draws its .float-bubble while the bot is running, so its presence IS the
  // bot state. Its classes tell us "executing" and "EA off". The app has no native bubble;
  // the page's bubble is the only one, and tapping it opens the bot console.
  var lastSent = '';

  function readBot() {
    swapLogo(); // re-applied if the page re-renders the bubble
    var b = document.querySelector('.float-bubble');
    var on = !!b;
    var exec = on && b.classList.contains('executing');
    var off = on && b.classList.contains('ea-off');
    var key = on + '|' + exec + '|' + off;
    if (key === lastSent) return;
    lastSent = key;
    try { window.AndroidNotify.botState(on, exec, off); } catch (e) {}
  }

  readBot(); // every page load starts by reporting OFF until the page proves otherwise
  setInterval(readBot, 1500);

  function watchDom() {
    try {
      new MutationObserver(readBot).observe(document.documentElement, {
        subtree: true, childList: true, attributes: true, attributeFilter: ['class']
      });
    } catch (e) {}
  }
  if (document.documentElement) watchDom();
  else document.addEventListener('DOMContentLoaded', watchDom);

  // XMLHttpRequest (axios)
  try {
    var oOpen = XMLHttpRequest.prototype.open;
    var oSend = XMLHttpRequest.prototype.send;
    XMLHttpRequest.prototype.open = function (m, u) {
      this.__lhUrl = String(u);
      return oOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.send = function () {
      var x = this;
      if (x.__lhUrl && x.__lhUrl.indexOf('EaSignal') > -1) {
        x.addEventListener('load', function () {
          try {
            var d = (x.responseType === '' || x.responseType === 'text') ? x.responseText : x.response;
            parse(d, x.__lhUrl);
          } catch (e) {}
        });
      }
      return oSend.apply(this, arguments);
    };
  } catch (e) {}

  // fetch
  try {
    var oFetch = window.fetch;
    if (oFetch) {
      window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var p = oFetch.apply(this, arguments);
        if (url.indexOf('EaSignal') > -1) {
          p.then(function (r) {
            try { r.clone().json().then(function (d) { parse(d, url); }).catch(function () {}); } catch (e) {}
          }).catch(function () {});
        }
        return p;
      };
    }
  } catch (e) {}
})();
