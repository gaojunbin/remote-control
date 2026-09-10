/* Local-only formula/diagram adapter. No user source is evaluated as JavaScript. */
(async function () {
  "use strict";
  const target = document.getElementById("content");
  const post = (error) => window.webkit?.messageHandlers?.renderStatus?.postMessage({
    height: Math.ceil(Math.max(target.scrollHeight, target.getBoundingClientRect().height) + 8),
    ...(error ? {error} : {})
  });
  const input = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(target.dataset.payload), x => x.charCodeAt(0))));
  const math = (source, node, display) => katex.render(source, node, {
    displayMode: display, throwOnError: false, trust: false, strict: "warn",
    maxSize: 10, maxExpand: 1000, output: "htmlAndMathml"
  });
  try {
    if (input.kind === "diagram") {
      mermaid.initialize({
        startOnLoad: false, securityLevel: "strict", htmlLabels: false,
        suppressErrorRendering: true, maxTextSize: 32768, maxEdges: 500,
        theme: input.dark ? "dark" : "neutral", fontFamily: "-apple-system, sans-serif", logLevel: "fatal",
        secure: ["secure", "securityLevel", "startOnLoad", "maxTextSize", "maxEdges", "htmlLabels", "dompurifyConfig", "theme", "themeVariables", "themeCSS", "fontFamily", "altFontFamily", "look", "layout", "suppressErrorRendering"]
      });
      const rendered = await mermaid.render("native-diagram", input.source);
      const parsed = new DOMParser().parseFromString(rendered.svg, "image/svg+xml");
      if (parsed.querySelector("parsererror") || parsed.documentElement.localName !== "svg") throw new Error("invalid diagram");
      parsed.querySelectorAll("script,foreignObject,iframe,object,embed,image,video,audio").forEach(node => node.remove());
      parsed.querySelectorAll("*").forEach(node => {
        for (const attribute of Array.from(node.attributes)) {
          if (attribute.name.toLowerCase().startsWith("on") || ((attribute.localName === "href" || attribute.name === "xlink:href") && !attribute.value.startsWith("#"))) node.removeAttribute(attribute.name);
        }
      });
      target.appendChild(document.importNode(parsed.documentElement, true));
    } else if (input.kind === "inlineMath") {
      for (const part of input.parts || []) {
        if (part.math !== undefined) {
          const node = document.createElement(part.display ? "div" : "span");
          math(part.math, node, !!part.display); target.appendChild(node);
        } else {
          let node = document.createElement(part.link ? "a" : part.code ? "code" : "span");
          node.textContent = part.text || "";
          if (part.strong) node.style.fontWeight = "600";
          if (part.em) node.style.fontStyle = "italic";
          if (part.strike) node.style.textDecoration = "line-through";
          if (part.link && /^(https?:|mailto:)/i.test(part.link)) node.setAttribute("href", part.link);
          target.appendChild(node);
        }
      }
    } else { math(input.source, target, true); }
    await document.fonts.ready;
    post();
    new ResizeObserver(() => post()).observe(target);
  } catch (_) {
    target.replaceChildren(document.createTextNode(input.source));
    post("This could not be drawn. The source is shown instead.");
  }
})();
