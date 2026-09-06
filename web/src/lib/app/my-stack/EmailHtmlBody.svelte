<script lang="ts">
    import {_} from "svelte-i18n";

    let {html, onReady}: {
        html: string;
        /** Fires once the mail has been measured, i.e. as soon as the iframe has a height. */
        onReady?: () => void;
    } = $props();

    let iframe = $state<HTMLIFrameElement | null>(null);

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

    /** The element the mail is wrapped in, and the only thing that is ever measured; see below. */
    const ROOT = "overmail-body";

    /**
     * How tall the iframe is made while it is measured. Mails are written in viewport units and
     * percentages often enough, and those resolve against the iframe — measuring at whatever
     * height we last set would let a `100vh` mail push its own frame taller on every pass, so
     * every measurement is taken at the same reference height instead: the window, which is what
     * a mail asking for a screenful means by one.
     */
    function probeHeight(): number {
        return Math.max(320, window.innerHeight);
    }

    const srcdoc = $derived(`<!doctype html>
<html><head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${CSP}">
<base target="_blank" rel="noopener noreferrer">
<style>
  html { overflow: hidden; }
  /* Transparent, so the card background shows through instead of a white block in dark mode. */
  html, body { margin: 0; padding: 0; background: transparent; }
  /* The box the mail is measured by. It contains what would otherwise escape it and be missed:
     flow-root keeps the margins of the mail's outermost elements inside instead of letting them
     collapse out through the body, and position keeps anything absolutely positioned from
     hanging off the document rather than off the mail. */
  #${ROOT} { display: flow-root; position: relative; }
  /* Mails are written for a fixed-width client; clamp the usual offenders to the card width. */
  img, video, table { max-width: 100%; }
  img { height: auto; }
  table { table-layout: fixed; }
</style>
</head><body><div id="${ROOT}">${html}</div></body></html>`);

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
        let reported = false;

        /**
         * Sizes the iframe to the mail. The height is set twice in the same turn: the frame is
         * put at the reference height, the mail is read back at that height — reading is what
         * lays it out — and the frame is then set to what was read. Nothing is painted in
         * between, so this is one resize and not a flicker, and because the measurement never
         * depends on the height we last set, it lands on the same number every time and the
         * observer below settles instead of feeding itself.
         */
        function measure(root: HTMLElement) {
            el!.style.height = `${probeHeight()}px`;
            const content = Math.ceil(root.scrollHeight);
            el!.style.height = `${content}px`;
        }

        function attach() {
            const doc = el!.contentDocument;
            const root = doc?.getElementById(ROOT);
            if (!doc || !root) return;

            inherit(el!, doc);
            measure(root);

            observer?.disconnect();
            // Only the mail's own box: the frame around it is the one we resize ourselves, so
            // observing that would just feed our own height changes back in.
            observer = new ResizeObserver(() => measure(root));
            observer.observe(root);
            // Images have no layout size until they arrive, and RO does not fire for that.
            for (const img of doc.images) img.addEventListener("load", () => measure(root));

            // After the first measurement, not before: this is what the card waits for, and
            // waiting for it is only worth anything once there is a height to show.
            if (!reported) {
                reported = true;
                onReady?.();
            }
        }

        el.addEventListener("load", attach);
        attach(); // srcdoc can already be parsed when the effect runs

        return () => {
            el.removeEventListener("load", attach);
            observer?.disconnect();
        };
    });
</script>

<!-- The height is the one thing here that is not bound: it is written by the measurement above,
     which has to put the frame at two heights within a single turn. Starting at zero rather than
     leaving it out keeps a mail that has not been measured yet from claiming the 150px an iframe
     is worth by default. -->
<iframe
        bind:this={iframe}
        title={$_('myStack.email.bodyTitle')}
        {srcdoc}
        sandbox={SANDBOX}
        referrerpolicy="no-referrer"
        loading="lazy"
        scrolling="no"
        class="block w-full border-none overflow-hidden"
        style="height: 0px"
></iframe>
