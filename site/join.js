// Reads the invite from the fragment (#v=1&control=...&key=...), the same fields as
// ovenlight://join?..., and never sends it anywhere: no requests, no storage, no analytics
// (the page's Content-Security-Policy blocks them too). Reaching this page means the link
// didn't open Ovenlight, so it offers the way to get the app and, for one who has it, Open in
// Ovenlight on Android, or on iPhone Copy Invite to paste into Ovenlight (any app can claim
// the ovenlight: scheme, so on iPhone the key never goes into a link of that kind).
(function () {
  "use strict";

  // An iPad runs the iPhone app, and iPadOS Safari sends a Mac's user agent by default.
  var onIPhone = /iPhone|iPad/.test(navigator.userAgent) ||
    (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
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

  var inviteLink = "";

  // Copies the invite link, with the clipboard API or else the selection copy older in-app
  // browsers have. The button says Copied only when one worked.
  function copy() {
    var button = document.getElementById("copy");
    function copied() { button.textContent = "Copied"; }
    function selected() {
      var box = document.createElement("textarea");
      box.value = inviteLink;
      box.setAttribute("readonly", "");
      box.style.position = "fixed";
      box.style.opacity = "0";
      document.body.appendChild(box);
      box.select();
      box.setSelectionRange(0, inviteLink.length);
      try { if (document.execCommand("copy")) copied(); } catch (e) { /* stays Copy Invite */ }
      document.body.removeChild(box);
    }
    if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(inviteLink).then(copied, selected);
    else selected();
  }
  document.getElementById("copy").addEventListener("click", copy);

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
      // which Android 10 to 12 leave out when they log the link. On iPhone, the link to
      // copy is the invite link as it came, on ovenlight.app, which Join with Invite takes
      // as pasted and which still opens Ovenlight from a message.
      var fields = params.toString();
      if (onAndroid) document.getElementById("open").href = "intent://join#" + fields + "#Intent;scheme=ovenlight;package=com.snowyghost.ovenlight;end";
      inviteLink = "https://ovenlight.app/join" + location.hash;
    }
    document.getElementById("open").hidden = !onAndroid;
    document.getElementById("copy").hidden = !onIPhone;
    document.getElementById("copy").textContent = "Copy Invite";
    document.getElementById("paste").hidden = !onIPhone;
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
})();
