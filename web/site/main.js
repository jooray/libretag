(function () {
  var b = document.getElementById("copynpub"), c = document.getElementById("npub");
  if (b && c) b.addEventListener("click", function () {
    var done = function () { var t = b.textContent; b.textContent = b.dataset.copied; setTimeout(function () { b.textContent = t; }, 1800); };
    if (navigator.clipboard) navigator.clipboard.writeText(c.textContent.trim()).then(done, function () {});
  });
  // Placeholder links do not navigate away.
  document.querySelectorAll("[data-placeholder]").forEach(function (a) {
    a.addEventListener("click", function (e) { e.preventDefault(); });
  });
})();
