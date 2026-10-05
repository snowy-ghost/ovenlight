// The docs pages' one script: a Copy button on each code block. Without it, the pages
// read the same. _headers names it in their Content-Security-Policy.
for (const pre of document.querySelectorAll(".prose pre")) {
  const wrap = document.createElement("div");
  wrap.className = "code";
  pre.replaceWith(wrap);
  const button = document.createElement("button");
  button.type = "button";
  button.textContent = "Copy";
  let timer;
  button.addEventListener("click", async () => {
    try {
      await navigator.clipboard.writeText(pre.textContent.replace(/\n$/, ""));
      button.textContent = "Copied";
    } catch {
      // No clipboard access: select the code, to copy by hand.
      getSelection().selectAllChildren(pre);
      button.textContent = "Selected";
    }
    clearTimeout(timer);
    timer = setTimeout(() => { button.textContent = "Copy"; }, 2000);
  });
  wrap.append(pre, button);
}
