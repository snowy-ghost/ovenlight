// Reads the invite from the fragment (#v=1&control=...&key=...), the same fields as
// ovenlight://join?..., and never sends it anywhere: no requests, no storage, no analytics
// (the page's Content-Security-Policy blocks them too). Reaching this page means the link
// didn't open Ovenlight, so it offers the ovenlight:// link and the way to get the app.
(function () {
  "use strict";

  // A display label, as Ovenlight shows it: no control or format characters (Swift's
  // controlCharacters, so no bidi overrides), trimmed, at most 64 characters.
  function label(text) {
    var clean = (text || "").replace(/[\p{Cc}\p{Cf}]/gu, "").trim();
    var chars = typeof Intl.Segmenter === "function"
      ? Array.from(new Intl.Segmenter().segment(clean), function (part) { return part.segment; })
      : Array.from(clean);
    return chars.slice(0, 64).join("");
  }

  // Also on a fragment change, since a page already open in a tab doesn't reload for one.
  function render() {
    var fields = location.hash.slice(1);
    var params = new URLSearchParams(fields);
    var required = ["control", "key", "app", "invite", "host"];
    var isInvite = params.get("v") === "1" && required.every(function (name) { return params.get(name); });
    var title = "You're invited to an app on Ovenlight";
    var guest = "";
    if (isInvite) {
      var owner = label(params.get("owner")) || "Someone";
      var app = label(params.get("name")) || label(params.get("app"));
      title = owner + " invited you to " + app;
      guest = label(params.get("to"));
      document.getElementById("open").href = "ovenlight://join?" + fields;
    }
    document.getElementById("title").textContent = title;
    document.title = isInvite ? title + " · Ovenlight" : title;
    // Ovenlight is iPhone only; elsewhere the button would do nothing.
    var onIPhone = /iPhone/.test(navigator.userAgent);
    document.getElementById("invite").hidden = !isInvite || !onIPhone;
    document.getElementById("elsewhere").hidden = !isInvite || onIPhone;
    document.getElementById("missing").hidden = isInvite;
    var invitee = document.getElementById("for");
    invitee.textContent = "This invite is for " + guest + ".";
    invitee.hidden = !guest;
  }
  render();
  window.addEventListener("hashchange", render);

  var testFlight = document.querySelector('meta[name="ovenlight-testflight"]').content.trim();
  if (/^https:\/\/testflight\.apple\.com\//.test(testFlight)) {
    var button = document.getElementById("testflight");
    button.href = testFlight;
    button.hidden = false;
    document.getElementById("ask").hidden = true;
  }
})();
