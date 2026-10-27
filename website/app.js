// Contatto offuscato: l'indirizzo non compare mai per intero nell'HTML.
(function () {
  document.querySelectorAll('.mail').forEach(function (el) {
    var addr = el.dataset.u + '@' + el.dataset.d + '.' + el.dataset.t;
    var a = document.createElement('a');
    a.href = 'mai' + 'lto:' + addr;
    a.textContent = addr;
    el.replaceWith(a);
  });
  document.querySelectorAll('[data-year]').forEach(function (el) {
    el.textContent = new Date().getFullYear();
  });
})();

// Lingua: alla prima visita la pagina iniziale si apre nella lingua del browser (l'italiano è il testo di riferimento);
// i link delle lingue ricordano la scelta. Solo la pagina iniziale viene deviata: privacy e termini restano dove punta il link.
(function () {
  try {
    document.querySelectorAll('.langs a').forEach(function (a) {
      a.addEventListener('click', function () { localStorage.setItem('ggcm-lang', a.getAttribute('hreflang')); });
    });
    var inFolder = /\/(de|en|es|fr)\//.test(location.pathname);
    var isHome = /(\/|index\.html)$/.test(location.pathname);
    if (inFolder || !isHome || localStorage.getItem('ggcm-lang')) return;
    var wanted = (navigator.language || 'it').slice(0, 2).toLowerCase();
    if (wanted === 'it') return;
    var target = ['de', 'en', 'es', 'fr'].indexOf(wanted) >= 0 ? wanted : 'en';
    location.replace(target + '/index.html');
  } catch (e) { /* senza storage o navigator la pagina resta com'è */ }
})();
