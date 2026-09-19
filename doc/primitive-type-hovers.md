## Prompt

I have another dilemma: Eclipse JDT LS doesn't show a hover when hovering over primitive types in the source code, even if inferred by `var`. Should I do the same for my Groovy LS? It does feel unnecessary since you can hover over the variable of the declaration using `def` or `var`, but it does not feel "feature complete". What should I do? Part of me feels like consistency with reference types and `var` means i should keep things the same.

## Response from Gemini 3.6 Flash

**Provide hover tooltips on primitive types, primitive keywords, and `def`/`var`.**

Skipping primitive hovers is a legacy optimization from older Java tooling, not an intentional UX feature. Modern language servers prioritize consistent, predictably interactive code over hiding information.

---

### Why Primitive Hovers Are Worth Keeping

* **Visual Predictability:** When a developer learns "hovering over a type name tells me about that type," breaking that rule for primitives creates a minor cognitive bump. The editor feels unresponsive on `int` or `boolean` if hovering yields nothing.
* **Contextual & Educational Value:** A hover on `int` or `byte` can display range boundaries (`-2,147,483,648` to `2,147,483,647` or `0..255`), bit-width, or relevant Groovy auto-boxing behavior (e.g., how Groovy transparently boxes `int` into `java.lang.Integer` when invoking methods).
* **`def` and `var` Type Inference:** When a variable is declared using `def` or `var`, hovering directly over the `def`/`var` keyword to reveal the inferred type (e.g., `Inferred type: java.lang.String`) is standard practice in modern LSP servers (such as Rust Analyzer, TypeScript, and gopls).

---

Treating all type keywords consistently makes your language server feel polished, predictable, and complete.
