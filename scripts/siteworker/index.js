// Answers range requests for the site's videos, which Workers static assets serve whole.
// Safari needs ranges to play a video: its first request asks for bytes 0-1 and expects
// just those two back. wrangler.jsonc runs this for the videos only, and every other file
// is served as a plain static asset.
export default {
  async fetch(request, env) {
    const asset = await env.ASSETS.fetch(request);
    if (asset.status !== 200) return asset;
    const headers = new Headers(asset.headers);
    headers.set("Accept-Ranges", "bytes");
    const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.get("Range") ?? "");
    if (!range || request.method !== "GET") return new Response(asset.body, { headers });

    const body = await asset.arrayBuffer();
    const size = body.byteLength;
    const [, first, last] = range;
    const start = first === "" ? Math.max(0, size - Number(last)) : Number(first);
    const end = first === "" || last === "" ? size - 1 : Math.min(Number(last), size - 1);
    if ((first === "" && last === "") || start > end) {
      return new Response(null, { status: 416, headers: { "Content-Range": `bytes */${size}` } });
    }
    headers.set("Content-Range", `bytes ${start}-${end}/${size}`);
    headers.set("Content-Length", String(end - start + 1));
    return new Response(body.slice(start, end + 1), { status: 206, headers });
  },
};
