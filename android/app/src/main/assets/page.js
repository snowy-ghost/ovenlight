// Ovenlight's page script, added at document start to every frame of an app's pages
// (PageBridge.kt). It hides service workers, which iOS doesn't run either; keeps the tracks
// getUserMedia hands out, so Ovenlight can stop them on lock and on close; and reports clicks
// on <a download>, with the name they give, and a blob's bytes, read at the click since pages
// often revoke a blob URL right after it. Only frames of the app's own origin get the bridge,
// and nothing they send grants anything: Ovenlight checks each download and stream itself.
(() => {
  delete Navigator.prototype.serviceWorker;
  const bridge = window.__ovenlight;
  if (!bridge) return;
  delete window.__ovenlight;
  const LIMIT = __LIMIT__;
  const send = message => bridge.postMessage(JSON.stringify(message));

  const tracks = new Set();
  const media = window.MediaDevices && MediaDevices.prototype;
  const getUserMedia = media && media.getUserMedia;
  if (getUserMedia) {
    media.getUserMedia = function (constraints) {
      send({ t: "capture" });
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
  bridge.addEventListener("message", event => {
    if (event.data !== "stop") return;
    tracks.forEach(track => track.stop());
    tracks.clear();
  });

  const blobs = new Map();
  const createObjectURL = URL.createObjectURL, revokeObjectURL = URL.revokeObjectURL;
  URL.createObjectURL = function (object) {
    const url = createObjectURL.call(URL, object);
    if (object instanceof Blob) blobs.set(url, object);
    return url;
  };
  URL.revokeObjectURL = function (url) {
    blobs.delete(url);
    return revokeObjectURL.call(URL, url);
  };

  const report = a => {
    if (!(a instanceof HTMLAnchorElement) || !a.hasAttribute("download") || !a.href) return;
    const href = a.href, name = a.download;
    if (!href.startsWith("blob:")) {
      if (href.length <= LIMIT) send({ t: "click", href, name });
      return;
    }
    // One message: the header's length (4 bytes, big-endian), the header, then the bytes.
    const blob = blobs.get(href);
    (blob ? Promise.resolve(blob) : fetch(href).then(response => response.blob()))
      .then(blob => {
        if (blob.size > LIMIT) return;
        const head = new TextEncoder().encode(JSON.stringify({ href, name, type: blob.type }));
        const size = new DataView(new ArrayBuffer(4));
        size.setUint32(0, head.length);
        return new Blob([size, head, blob]).arrayBuffer();
      })
      .then(bytes => bytes && bridge.postMessage(bytes), () => {});
  };
  // From the path, so an anchor inside a shadow root counts too.
  addEventListener("click", event => report(event.composedPath().find(node => node instanceof HTMLAnchorElement)), true);
  // A script's click on an anchor outside the document never passes through the document.
  const anchor = HTMLAnchorElement.prototype, click = anchor.click, dispatchEvent = EventTarget.prototype.dispatchEvent;
  anchor.click = function () {
    if (!this.isConnected) report(this);
    return click.call(this);
  };
  anchor.dispatchEvent = function (event) {
    if (!this.isConnected && event.type === "click") report(this);
    return dispatchEvent.call(this, event);
  };
})();
