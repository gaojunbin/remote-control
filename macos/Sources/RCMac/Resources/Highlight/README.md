# Highlight

The chat's Markdown and syntax highlighting, run in JavaScriptCore by
`Chat/Markdown/MarkdownEngine.swift` so a message comes out of the same code the
web runs it through.

`markdown.bundle.js` is react-markdown 10's processor as `web/src/features/chat/Markdown.tsx`
configures it — remark-parse 11, remark-gfm 4.0.1, remark-rehype 11.1.2 with
`allowDangerousHtml`, rehype-highlight 7.0.2 with `detect: false` — followed by
react-markdown's own `post` step (raw HTML becomes text, URLs go through
`defaultUrlTransform`). It defines one global, `rcMarkdown(text)`, which returns
the resulting hast as JSON: an element is `[tag, properties, children]`, a text
node a string. Highlighting is lowlight 3.3.0 on the highlight.js it depends on,
11.11.2 with its 37 common languages — the copy the web actually highlights with;
the web's own `highlight.js` 11.12.0 only supplies `styles/github.css`, whose
colours are in `Chat/Markdown/HighlightTheme.swift`.

Built from the web's installed packages with esbuild 0.28.2, from a one-file entry
that imports those packages, applies the `post` step and assigns `rcMarkdown`:

```
NODE_PATH=web/node_modules web/node_modules/.bin/esbuild entry.js --bundle \
  --format=iife --platform=neutral --main-fields=module,main --conditions=worker \
  --target=es2020 --minify --legal-comments=none \
  --banner:js='var console=globalThis.console||{log(){},info(){},warn(){},error(){},debug(){}};' \
  --outfile=markdown.bundle.js
```

`--platform=neutral` with the `worker` condition keeps every package off its DOM
build (`decode-named-character-reference` has one), because JavaScriptCore has no
`document`. Rebuild it whenever the web's Markdown or highlighting packages change.
The licenses are beside it: `LICENSE-highlight.js.txt` (BSD-3-Clause) and
`LICENSES-markdown.txt` (every other bundled package, MIT or ISC).
