<script lang="ts">
    import {_} from "svelte-i18n";

    let {html}: { html: string } = $props();

    let iframe = $state<HTMLIFrameElement | null>(null);
    /** Content height in px; the iframe is sized to it so it never scrolls itself. */
    let height = $state(0);

    // Nothing a mail brings along may execute, phone home or navigate us away. The sandbox drops
    // `allow-scripts`, so no script in the mail runs at all; the CSP is the second lock and kills
    // every remote load except images, which are the one thing a mail is legitimately made of.
    // `allow-same-origin` is what lets *us* read the document to measure it — harmless without
    // scripts, and the popup grants only cover the user clicking a link.
    const SANDBOX = "allow-same-origin allow-popups allow-popups-to-escape-sandbox";
    const CSP = [
        "default-src 'none'",
        "img-src data: blob: https: http:",
        "style-src 'unsafe-inline'",
        "font-src data:",
        "form-action 'none'",
    ].join("; ");

    const srcdoc = $derived(`<!doctype html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${CSP}">
<base target="_blank" rel="noopener noreferrer">
<style>
  html { overflow: hidden; }
  /* Transparent, so the card background shows through instead of a white block in dark mode. */
  html, body { margin: 0; padding: 0; background: transparent; }
  /* Mails are written for a fixed-width client; clamp the usual offenders to the card width. */
  img, video, table { max-width: 100%; }
  img { height: auto; }
  table { table-layout: fixed; }
</style>
</head><body>${html}</body></html>`);

    function measure(doc: Document) {
        // Both, because margin collapsing can leave one of them short.
        height = Math.ceil(Math.max(doc.documentElement.scrollHeight, doc.body?.scrollHeight ?? 0));
    }

    /** Mails rarely set a colour for plain text, so hand them the card's. */
    function inherit(el: HTMLIFrameElement, doc: Document) {
        const {color, fontFamily, fontSize, lineHeight} = getComputedStyle(el);
        Object.assign(doc.documentElement.style, {color, fontFamily, fontSize, lineHeight});
    }

    $effect(() => {
        const el = iframe;
        srcdoc; // re-attach when the mail changes and the iframe reloads
        if (!el) return;

        let observer: ResizeObserver | null = null;

        function attach() {
            const doc = el!.contentDocument;
            if (!doc?.body) return;

            inherit(el!, doc);
            measure(doc);

            observer?.disconnect();
            observer = new ResizeObserver(() => measure(doc));
            // Only the body: the root box is the one we resize ourselves, so observing it would
            // just feed our own height changes back in.
            observer.observe(doc.body);
            // Images have no layout size until they arrive, and RO does not fire for that.
            for (const img of doc.images) img.addEventListener("load", () => measure(doc));
        }

        el.addEventListener("load", attach);
        attach(); // srcdoc can already be parsed when the effect runs

        return () => {
            el.removeEventListener("load", attach);
            observer?.disconnect();
        };
    });
</script>

<iframe
        bind:this={iframe}
        title={$_('myStack.email.bodyTitle')}
        {srcdoc}
        sandbox={SANDBOX}
        referrerpolicy="no-referrer"
        loading="lazy"
        scrolling="no"
        class="block w-full border-none overflow-hidden"
        style="height: {height}px"
></iframe>
