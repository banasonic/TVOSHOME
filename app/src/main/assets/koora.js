/* Extracts today's matches from kooora.com "مباريات اليوم" (works on a live document or a DOMParser document). ES5 on purpose (old TV WebViews). */
function kooraExtract(doc) {
  var root = doc.body || doc.documentElement, out = [], seen = {}, comp = null, round = '', cur = null, started = false, skipUntil = null;
  function dec(h) { try { return decodeURIComponent(h || ''); } catch (e) { return h || ''; } }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function texts(el) {
    var r = [], w = doc.createTreeWalker(el, 4, null, false), n, t;
    while ((n = w.nextNode())) { t = clean(n.nodeValue); if (t) r.push(t); }
    return r;
  }
  var w = doc.createTreeWalker(root, 5, null, false), n;
  while ((n = w.nextNode())) {
    if (skipUntil) { if (skipUntil.contains(n)) continue; skipUntil = null; }
    if (n.nodeType === 1) {
      var tg = n.nodeName;
      if (tg === 'H1') { if (/المباريات والنتائج/.test(n.textContent || '')) started = true; continue; }
      if (!started || tg !== 'A') continue;
      var href = dec(n.getAttribute('href') || '');
      if (href.indexOf('/مباراة/') > -1) {
        skipUntil = n;
        var id = href.replace(/[?#].*$/, '').split('/').filter(function (x) { return x; }).pop();
        if (seen[id]) { cur = null; continue; }
        seen[id] = 1;
        var ts = texts(n), k = -1, i;
        for (i = 0; i < ts.length; i++) { if (/^\d{1,2}:\d{2}$/.test(ts[i])) { k = i; break; } }
        var nm = k < 0 ? ts.slice(0, 4) : ts.slice(0, k), h, a;
        if (nm.length === 4) { h = nm[0]; a = nm[2]; }
        else if (nm.length === 2) { h = nm[0]; a = nm[1]; }
        else { h = nm[0]; a = nm[Math.ceil(nm.length / 2)] || ''; }
        var m = { id: id, c: comp ? comp.n : '', k: comp ? comp.k : '', r: round, h: h || '', a: a || '',
          t: k >= 0 ? ts[k] : '', st: k < 0 ? ts.slice(4).join(' ') : '', x: k >= 0 ? ts.slice(k + 1).join(' ') : '', ch: [] };
        out.push(m); cur = m; continue;
      }
      if (href.indexOf('/مسابقة/') > -1 && n.querySelector('img')) {
        skipUntil = n; var t2 = texts(n); comp = { n: t2[0] || '', k: t2[1] || '' }; round = ''; cur = null; continue;
      }
      if (cur && /^https?:/.test(href) && href.indexOf('kooora.com') < 0 && !(n.closest && n.closest('footer,nav,header'))) {
        var t3 = clean(n.textContent);
        if (t3 && cur.ch.indexOf(t3) < 0 && cur.ch.length < 14) cur.ch.push(t3);
      }
      continue;
    }
    if (!started || !comp) continue;
    var t = clean(n.nodeValue);
    if (!t || t === 'إعلان' || t === 'شاهد مباشرة على') continue;
    if (n.parentNode && n.parentNode.closest && n.parentNode.closest('a')) continue;
    if (t.length < 30) round = t;
  }
  return { m: out };
}
