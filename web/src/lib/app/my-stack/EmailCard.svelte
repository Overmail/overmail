<script module lang="ts">
    /** One address as it stood in a header field; `name` is absent for a bare address. */
    export type EmailCardParticipant = {
        name?: string;
        address: string;
    };
</script>

<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {Button} from "$lib/components/ui/button";
    import {ButtonGroup} from "$lib/components/ui/button-group";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {cn} from "$lib/utils.js";

    let {
        sender,
        sent,
        to,
        cc = [],
        bcc = [],
        subject,
        tags = [],
        body,
        html,
        dim = 0,
        tint = "transparent",
        class: className,
    }: {
        sender: EmailCardParticipant & { avatarUrl?: string };
        /** Formatted for display already: the card does no locale work of its own. */
        sent: string;
        to: EmailCardParticipant[];
        cc?: EmailCardParticipant[];
        bcc?: EmailCardParticipant[];
        subject: string;
        tags?: string[];
        /** The text part, or the HTML part flattened. Absent while the body's own request is still
            out; the card then shows its shape. */
        body?: string;
        /** The HTML part as the mail carried it, when it carried one. Shown in preference to `body`. */
        html?: string;
        /** 0…1: how far the card is faded into the background while it sits behind another one. */
        dim?: number;
        /** Colour washed over the whole card, e.g. the decision it was classified with. */
        tint?: string;
        class?: string;
    } = $props();

    const fields = $derived([
        {label: "To:", participants: to},
        {label: "CC:", participants: cc},
        {label: "BCC:", participants: bcc},
    ].filter((field) => field.participants.length > 0));

    function formatParticipant(participant: EmailCardParticipant): string {
        return participant.name ? `${participant.name} (${participant.address})` : participant.address;
    }

    /** Up to two initials, from the display name if there is one and the address otherwise. */
    const initials = $derived(
        (sender.name ?? sender.address)
            .split(/[\s.@_-]+/)
            .filter(Boolean)
            .slice(0, 2)
            .map((part) => part[0]!.toUpperCase())
            .join(""),
    );

    /** Which part the reader switched to, or null while the card is still on its default. */
    let chosen = $state<"html" | "text" | null>(null);

    // HTML whenever the mail carried it, and the reader's pick on top of that. Read off `html`
    // rather than kept in sync with it: the body arrives after the header does, so what the card
    // shows has to follow it in.
    const view = $derived(html ? (chosen ?? "html") : "text");

    /** Both parts there: only then is there anything to switch between. */
    const canSwitch = $derived(Boolean(html) && Boolean(body));

    /** Neither part has arrived yet, as opposed to a mail whose body is genuinely empty. */
    const isLoading = $derived(body === undefined && html === undefined);

    // A stranger's markup goes into a frame rather than through `{@html}`: the sandbox keeps
    // scripts, forms and plugins from running, and the frame's own document keeps the mail's CSS
    // -- which is written as if it owned the page -- off the rest of the app. `allow-same-origin`
    // is only there so the rendered height can be read back out below; with no `allow-scripts`
    // alongside it, it hands the mail nothing.
    const FRAME_SANDBOX = "allow-same-origin";

    /**
     * The mail wrapped in a document of its own.
     *
     * White with dark text whatever the app's theme is: mail is written against a light client and
     * turns unreadable on a dark background. Links target a window the sandbox does not allow, so
     * a click goes nowhere instead of steering the frame away from the mail.
     */
    const frameDocument = $derived(html ? `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="script-src 'none'; object-src 'none'; frame-src 'none'; form-action 'none'">
<base target="_blank">
<style>
:root { color-scheme: light; }
body { margin: 0; background: #fff; color: #000; font-family: system-ui, sans-serif; overflow-wrap: anywhere; }
img { max-width: 100%; height: auto; }
</style>
</head>
<body>${html}</body>
</html>` : "");

    let frame = $state<HTMLIFrameElement | null>(null);

    /** What the frame's document turned out to be, so the frame can be as tall as the mail. */
    let frameHeight = $state(0);

    /** Bumped on every load, which is what tells the effect below there is a document to measure. */
    let loads = $state(0);

    // The frame does not scroll: the card is one surface, so the frame grows to the whole mail and
    // the card's own container is what scrolls. Images arrive after the load event on a slow line
    // and reflow the mail, hence the observer rather than a single measurement.
    $effect(() => {
        loads;

        const document = frame?.contentDocument;
        if (!document?.body) return;

        const measure = () => (frameHeight = Math.ceil(document.body.scrollHeight));
        measure();

        const observer = new ResizeObserver(measure);
        observer.observe(document.body);
        return () => observer.disconnect();
    });
</script>

<!-- box-shadow rather than drop-shadow: the card is an opaque rounded rectangle, so the cheaper
     one looks the same, and a filter would force its own render surface on every card in the
     stack. `class` goes last, so the caller can place and rotate the card in the stack. -->
<div class={cn("relative flex flex-col w-3xl h-fit bg-background rounded-2xl shadow-2xl", className)}>
    <div class="flex flex-row items-center justify-between gap-6 px-8 pt-8">
        <div class="flex flex-row gap-4 items-center">
            <Avatar.Root class="size-12">
                <Avatar.Image src={sender.avatarUrl} alt="" />
                <Avatar.Fallback class="text-base">{initials}</Avatar.Fallback>
            </Avatar.Root>
            <div class="flex flex-col">
                <span class="font-medium text-lg">{sender.name ?? sender.address}</span>
                {#if sender.name}
                    <span class="font-light text-base">{sender.address}</span>
                {/if}
            </div>
        </div>

        <div>
            <span class="font-light text-accent-foreground">{sent}</span>
        </div>
    </div>

    <div class="px-8 pt-4 flex flex-row flex-wrap items-center gap-x-10">
        {#each fields as field (field.label)}
            <div class="flex flex-row items-baseline gap-1">
                <span class="font-bold text-muted-foreground px-1 py-0.5 rounded-sm w-16">{field.label}</span>
                <span>{field.participants.map(formatParticipant).join(", ")}</span>
            </div>
        {/each}
    </div>

    <div class="px-8 pt-6 flex flex-row flex-wrap items-center gap-x-8 text-xl">
        {subject}
    </div>

    {#if tags.length}
        <div class="px-8 pt-3 flex flex-row flex-wrap items-center gap-1">
            {#each tags as tag (tag)}
                <span class="rounded-sm bg-muted px-2 py-0.5 text-sm text-muted-foreground">{tag}</span>
            {/each}
        </div>
    {/if}

    <div class="mx-4 my-4 h-px bg-accent"></div>

    {#if isLoading}
        <!-- Lines in the shape of text rather than a spinner: everything above this is already
             there, so the card reads as one that is still filling in rather than as one that is
             loading. -->
        <div class="pb-8 px-8 flex flex-col gap-3">
            <Skeleton class="h-4 w-full" />
            <Skeleton class="h-4 w-11/12" />
            <Skeleton class="h-4 w-4/6" />
        </div>
    {:else}
        {#if canSwitch}
            <div class="px-8 pb-4">
                <ButtonGroup role="tablist">
                    <Button
                            variant={view === "html" ? "secondary" : "outline"}
                            size="sm"
                            role="tab"
                            aria-selected={view === "html"}
                            onclick={() => (chosen = "html")}
                    >
                        HTML
                    </Button>
                    <Button
                            variant={view === "text" ? "secondary" : "outline"}
                            size="sm"
                            role="tab"
                            aria-selected={view === "text"}
                            onclick={() => (chosen = "text")}
                    >
                        Text
                    </Button>
                </ButtonGroup>
            </div>
        {/if}

        {#if view === "html"}
            <div class="pb-8 px-8">
                <iframe
                        bind:this={frame}
                        title="Inhalt der E-Mail"
                        srcdoc={frameDocument}
                        sandbox={FRAME_SANDBOX}
                        scrolling="no"
                        class="block w-full rounded-lg bg-background"
                        style="height: {frameHeight}px"
                        onload={() => loads++}
                ></iframe>
            </div>
        {:else}
            <div class="pb-8 px-8 whitespace-pre-wrap wrap-anywhere">
                {body}
            </div>
        {/if}
    {/if}

    <!-- Dimmed by laying the background colour on top rather than lowering the card's opacity: a
         card in the stack has to stay solid, or the cards behind it show through. -->
    <div
            class="pointer-events-none absolute inset-0 rounded-2xl bg-background transition-opacity duration-500 motion-reduce:transition-none"
            style="opacity: {dim}"
    ></div>

    <!-- The tint animates as a colour rather than as the opacity of a coloured layer, so it also
         washes back out when the mail is pulled into the stack again. Short: a mail that is being
         decided on is off the screen in a fraction of the time the card takes to travel, so a slow
         fade would never be seen. -->
    <div
            class="pointer-events-none absolute inset-0 rounded-2xl transition-colors duration-150 motion-reduce:transition-none"
            style="background-color: {tint}"
    ></div>
</div>
