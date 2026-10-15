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
