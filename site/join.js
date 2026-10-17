// Reads the invite from the fragment (#v=1&control=...&key=...), the same fields as
// ovenlight://join?..., and never sends it anywhere: no requests, no storage, no analytics
// (the page's Content-Security-Policy blocks them too). Reaching this page means the link
// didn't open Ovenlight, so it offers Open in Ovenlight and the way to get the app.
(function () {
  "use strict";

  var onIPhone = /iPhone/.test(navigator.userAgent);
  var onAndroid = /Android/.test(navigator.userAgent);

  // A display label, as Ovenlight shows it: no control or format characters (Swift's
  // controlCharacters, so no bidi overrides) except the joiners emoji and scripts such as
  // Persian need (U+200C, U+200D) and the tags in flags like England's (U+E0020 to
  // U+E007F), trimmed, at most 64 characters.
  function label(text) {
    var clean = (text || "").replace(/(?![\u200C\u200D\u{E0020}-\u{E007F}])[\p{Cc}\p{Cf}]/gu, "").trim();
    var chars = typeof Intl.Segmenter === "function"
      ? Array.from(new Intl.Segmenter().segment(clean), function (part) { return part.segment; })
      : Array.from(clean);
    return chars.slice(0, 64).join("");
  }

  // Also on a fragment change, since a page already open in a tab doesn't reload for one.
  function render() {
    var params = new URLSearchParams(location.hash.slice(1));
    var required = ["control", "key", "app", "invite", "host"];
    var isInvite = params.get("v") === "1" && required.every(function (name) { return params.get(name); });
    var title = "You're invited to an app on Ovenlight";
    var guest = "";
    if (isInvite) {
      var owner = label(params.get("owner")) || "Someone";
      var app = label(params.get("name")) || label(params.get("app"));
      title = owner + " invited you to " + app;
      guest = label(params.get("to"));
      // Rebuilt from the parsed fields, so a "#" or anything else in a crafted link stays
      // inside a field's value. On Android, an intent: link names Ovenlight's package, since
      // any app can claim the ovenlight: scheme, and carries the fields in its fragment,
      // which Android 10 to 12 leave out when they log the link.
      var fields = params.toString();
      document.getElementById("open").href = onAndroid
        ? "intent://join#" + fields + "#Intent;scheme=ovenlight;package=com.snowyghost.ovenlight;end"
        : "ovenlight://join?" + fields;
    }
    document.getElementById("title").textContent = title;
    document.title = isInvite ? title + " · Ovenlight" : title;
    // Ovenlight is for iPhone and Android; elsewhere the button would do nothing.
    var onPhone = onIPhone || onAndroid;
    document.getElementById("invite").hidden = !isInvite || !onPhone;
    document.getElementById("elsewhere").hidden = !isInvite || onPhone;
    document.getElementById("missing").hidden = isInvite;
    var invitee = document.getElementById("for");
    invitee.textContent = "This invite is for " + guest + ".";
    invitee.hidden = !guest;
  }
  render();
  window.addEventListener("hashchange", render);

  // A phone gets its own store's badge, and anywhere else both show.
  document.getElementById("ask").hidden = onAndroid;
  document.getElementById("ask-play").hidden = onIPhone;

  var testFlight = document.querySelector('meta[name="ovenlight-testflight"]').content.trim();
  if (!onAndroid && /^https:\/\/testflight\.apple\.com\//.test(testFlight)) {
    var button = document.getElementById("testflight");
    button.href = testFlight;
    button.hidden = false;
    document.getElementById("ask").hidden = true;
  }
})();
