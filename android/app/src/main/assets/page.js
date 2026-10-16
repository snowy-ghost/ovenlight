// Ovenlight's page script, added at document start to every frame of an app's pages
// (PageBridge.kt). It hides service workers, which iOS doesn't run either; keeps the tracks
// getUserMedia hands out, so Ovenlight can stop them on lock and on close; and reports clicks
// on <a download>, with the name they give, and a blob's bytes, read at the click since pages
// often revoke a blob URL right after it.
//
// Ovenlight hands the main frame alone a MessagePort, posted to the window, which this script
// takes before any page script can listen. No frame has anything else to reach Ovenlight with,
// so a frame of the app's origin passes its reports up to the main frame, whose send() checks
// each one. Nothing a page sends grants anything: Ovenlight checks each download and stream.
(() => {
  delete Navigator.prototype.serviceWorker;
  const LIMIT = __LIMIT__, HREF_LIMIT = __HREF_LIMIT__, HEADER_LIMIT = __HEADER_LIMIT__;

  // The built-ins a report passes through, taken now, before any page script runs, and called
  // through Reflect.apply, so replacing one later doesn't reach here. That is best effort: a page
  // can still bend what a report says (Ovenlight trusts none). send() is the guarantee, on size.
  const apply = Reflect.apply, describe = Object.getOwnPropertyDescriptor;
  const getter = (type, name) => describe(type.prototype, name).get;
  const stringify = JSON.stringify, later = setTimeout, then = Promise.prototype.then;
  const startsWith = String.prototype.startsWith, clock = performance, now = Performance.prototype.now;
  const byteLength = getter(ArrayBuffer, "byteLength");
  const BlobType = Blob, blobSize = getter(Blob, "size"), blobKind = getter(Blob, "type"), blobBytes = Blob.prototype.arrayBuffer;
  const encoder = new TextEncoder(), encode = TextEncoder.prototype.encode;
  const typedLength = getter(Object.getPrototypeOf(Uint8Array), "length");
  const DataViewType = DataView, setUint32 = DataView.prototype.setUint32, ArrayBufferType = ArrayBuffer;
  const doFetch = fetch, responseBlob = Response.prototype.blob;
  const MapType = Map, mapGet = Map.prototype.get, mapSet = Map.prototype.set, mapDelete = Map.prototype.delete;
  const anchorHref = getter(HTMLAnchorElement, "href"), anchorDownload = getter(HTMLAnchorElement, "download");
  const hasAttribute = Element.prototype.hasAttribute, connected = getter(Node, "isConnected");
  const composedPath = Event.prototype.composedPath, prevented = getter(Event, "defaultPrevented"), eventType = getter(Event, "type");
  const listen = EventTarget.prototype.addEventListener, stopNow = Event.prototype.stopImmediatePropagation;
  const eventData = getter(MessageEvent, "data"), eventSource = getter(MessageEvent, "source");
  const eventOrigin = getter(MessageEvent, "origin"), eventPorts = getter(MessageEvent, "ports");
  const portPost = MessagePort.prototype.postMessage, portStart = MessagePort.prototype.start, portClose = MessagePort.prototype.close;
  const windowPost = window.postMessage;
  const top = window.top, isMain = window === top, ownOrigin = location.origin;
  // Ovenlight's offer of a port, and the page script's answer once it holds one (PageBridge.kt).
  const OFFER = "__OFFER__", ANSWER = "__ANSWER__", STOP = "__STOP__", RELAY = "ovenlight-relay";
  let port = null;

  // The one way out. A string goes as UTF-16 and Java caps it by length, so its length is
  // measured; an ArrayBuffer by its own byte length. Anything over the caps never leaves, and big
  // messages share a budget of one blob's worth a second, so a page can't queue gigabytes in
  // Ovenlight one capped message at a time. Small ones (capture reports) don't count.
  const STRING_LIMIT = HREF_LIMIT + HEADER_LIMIT, BUFFER_LIMIT = LIMIT + HEADER_LIMIT + 4, SMALL = 64;
  let budget = BUFFER_LIMIT, budgetAt = apply(now, clock, []);
  const send = message => {
    let size;
    if (typeof message === "string") {
      size = message.length * 2;
      if (message.length > STRING_LIMIT) return;
    } else {
      try {
        size = apply(byteLength, message, []);
      } catch (e) {
        return;
      }
      if (size > BUFFER_LIMIT) return;
    }
    if (size > SMALL * 2) {
      const at = apply(now, clock, []);
      budget += (at - budgetAt) * BUFFER_LIMIT / 1000;
      if (!(budget <= BUFFER_LIMIT)) budget = BUFFER_LIMIT;
      budgetAt = at;
      if (size > budget) return;
      budget -= size;
    }
    if (!isMain) return relay(message);
    if (port) apply(portPost, port, [message]);
  };
  // A frame's report goes to the main frame, which puts it through its own send().
  const relay = message => {
    try {
      apply(windowPost, top, [{ [RELAY]: message }, { targetOrigin: ownOrigin, transfer: typeof message === "string" ? [] : [message] }]);
    } catch (e) {}
  };
  const report = message => send(apply(stringify, undefined, [message]));

  // Tracks a page takes past these wrappers are the system check's to catch (PageCapture.kt).
  const tracks = new Set();
  const media = window.MediaDevices && MediaDevices.prototype;
  const getUserMedia = media && media.getUserMedia;
  if (getUserMedia) {
    media.getUserMedia = function (constraints) {
      report({ t: "capture" });
      return getUserMedia.call(this, constraints).then(stream => {
        stream.getTracks().forEach(track => tracks.add(track));
        return stream;
      });
    };
    const cloneTrack = MediaStreamTrack.prototype.clone, cloneStream = MediaStream.prototype.clone;
    MediaStreamTrack.prototype.clone = function () {
      const copy = cloneTrack.call(this);
      if (tracks.has(this)) tracks.add(copy);
      return copy;
    };
    MediaStream.prototype.clone = function () {
      const copy = cloneStream.call(this);
      const kept = this.getTracks().map(track => tracks.has(track));
      copy.getTracks().forEach((track, i) => kept[i] && tracks.add(track));
      return copy;
    };
  }
  // Stopping: the main frame stops its own tracks and sends the stop to every frame below it,
  // nested ones too; only those of this origin take it.
  const stopTracks = () => {
    tracks.forEach(track => track.stop());
    tracks.clear();
  };
  const stopBelow = frame => {
    let count = 0;
    try {
      // A page can set its own window's length; Chromium allows far fewer frames than this.
      count = frame.length > 1000 ? 1000 : frame.length;
    } catch (e) {}
    for (let i = 0; i < count; i++) {
      try {
        const child = frame[i];
        apply(windowPost, child, [STOP, ownOrigin]);
        stopBelow(child);
      } catch (e) {}
    }
  };
  // Only trusted events count: a page can dispatch a MessageEvent but never a trusted one. The
  // offer comes from no window and no origin (WebView's post); relays and stops come from a
  // window of this origin.
  const heard = event => {
    if (!event.isTrusted) return;
    const data = apply(eventData, event, []), source = apply(eventSource, event, []);
    if (isMain && source === null && data === OFFER && apply(eventOrigin, event, []) === "") {
      const ports = apply(eventPorts, event, []);
      if (ports.length !== 1) return;
      apply(stopNow, event, []);
      // Ovenlight offers again until it hears back, and the newest offer is the one it keeps.
      if (port) apply(portClose, port, []);
      port = ports[0];
      apply(listen, port, ["message", message => {
        if (apply(eventData, message, []) !== STOP) return;
        stopTracks();
        stopBelow(window);
      }]);
      apply(portStart, port, []);
      apply(portPost, port, [ANSWER]);
    } else if (source !== null && apply(eventOrigin, event, []) === ownOrigin) {
      if (isMain && typeof data === "object" && data !== null && describe(data, RELAY)) {
        apply(stopNow, event, []);
        send(describe(data, RELAY).value);
      } else if (!isMain && source === top && data === STOP) {
        apply(stopNow, event, []);
        stopTracks();
      }
    }
  };
  const listenFirst = () => apply(listen, window, ["message", heard, true]);
  listenFirst();
  // document.open() takes every listener off the window, this script's too, so it goes back on as
  // open() or write() returns, which keeps the channel working for a page that opens its document.
  // It isn't a boundary: page code that runs during open() or write() (a custom element's
  // reaction, a removed frame's unload, a script write() adds), or another realm's open(), can
  // put its own listener first and take the next offer. Such a page gains nothing new: reports
  // it can already send through a relay from itself, a stop it could already defeat (stopping
  // rests on Ovenlight's system check, PageCapture.kt), and memory it can already spend, since
  // WebView hands Ovenlight every console message whole.
  for (const name of ["open", "write", "writeln"]) {
    const original = Document.prototype[name];
    Document.prototype[name] = function (...args) {
      try {
        return apply(original, this, args);
      } finally {
        listenFirst();
      }
    };
  }

  const blobs = new MapType();
  const isBlob = object => {
    try {
      apply(blobSize, object, []);
      return true;
    } catch (e) {
      return false;
    }
  };
  const createObjectURL = URL.createObjectURL, revokeObjectURL = URL.revokeObjectURL;
  URL.createObjectURL = function (object) {
    const url = apply(createObjectURL, URL, [object]);
    if (isBlob(object)) apply(mapSet, blobs, [url, object]);
    return url;
  };
  URL.revokeObjectURL = function (url) {
    apply(mapDelete, blobs, [url]);
    return apply(revokeObjectURL, URL, [url]);
  };

  // A blob's message: the header's length (4 bytes, big-endian), the header, then the bytes.
  const sendBlob = (href, name, blob) => {
    if (apply(blobSize, blob, []) > LIMIT) return;
    const head = apply(encode, encoder, [apply(stringify, undefined, [{ href, name, type: apply(blobKind, blob, []) }])]);
    const size = new DataViewType(new ArrayBufferType(4));
    apply(setUint32, size, [0, apply(typedLength, head, [])]);
    apply(then, apply(blobBytes, new BlobType([size, head, blob]), []), [send, () => {}]);
  };

  // What a click names is read at once, since pages revoke a blob URL right after it, and sent
  // only once the click's dispatch is over, if no handler cancelled it.
  const clicked = (a, event) => {
    let href, name;
    try {
      href = apply(anchorHref, a, []);
      name = apply(anchorDownload, a, []);
    } catch (e) {
      return;
    }
    if (!href || !apply(hasAttribute, a, ["download"])) return;
    const blobURL = apply(startsWith, href, ["blob:"]), blob = blobURL && apply(mapGet, blobs, [href]);
    if (blob ? apply(blobSize, blob, []) > LIMIT : href.length > HREF_LIMIT) return;
    // A blob URL this script didn't see made (a worker's, say) is fetched now, before it's revoked.
    const read = blobURL && !blob && apply(then, doFetch(href), [response => apply(responseBlob, response, [])]);
    later(() => {
      if (apply(prevented, event, [])) return;
      if (!blobURL) return report({ t: "click", href, name });
      if (blob) return sendBlob(href, name, blob);
      apply(then, read, [fetched => isBlob(fetched) && sendBlob(href, name, fetched), () => {}]);
    });
  };
  // From the path, so an anchor inside a shadow root counts too.
  const anchorIn = event => {
    const path = apply(composedPath, event, []);
    for (let i = 0; i < path.length; i++) if (path[i] instanceof HTMLAnchorElement) return path[i];
  };
  apply(listen, window, ["click", event => { const a = anchorIn(event); if (a) clicked(a, event); }, true]);
  // A script's click on an anchor outside the document never passes through the document.
  const anchor = HTMLAnchorElement.prototype, click = anchor.click, dispatchEvent = EventTarget.prototype.dispatchEvent;
  anchor.click = function () {
    if (!apply(connected, this, [])) apply(listen, this, ["click", event => clicked(this, event), { capture: true, once: true }]);
    return apply(click, this, []);
  };
  anchor.dispatchEvent = function (event) {
    if (!apply(connected, this, []) && apply(eventType, event, []) === "click") clicked(this, event);
    return apply(dispatchEvent, this, [event]);
  };

  // WebView's date and time pickers are windows of their own, which the lock taking the page
  // away doesn't close; only the input's blur does. So a page going hidden (the lock, leaving
  // Ovenlight, another app) closes the one its frame has open.
  const blur = HTMLElement.prototype.blur, activeElement = getter(Document, "activeElement");
  const hidden = getter(Document, "hidden"), inputType = getter(HTMLInputElement, "type");
  const PICKERS = new Set(["date", "time", "datetime-local", "month", "week"]), has = Set.prototype.has;
  apply(listen, document, ["visibilitychange", () => {
    if (!apply(hidden, document, [])) return;
    const element = apply(activeElement, document, []);
    if (element instanceof HTMLInputElement && apply(has, PICKERS, [apply(inputType, element, [])])) apply(blur, element, []);
  }, true]);
})();
