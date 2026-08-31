<script lang="ts">
    import * as Avatar from "$lib/components/ui/avatar";
    import {Spinner} from "$lib/components/ui/spinner";
    import {cn, initials, scrollIntoViewWithin} from "$lib/utils.js";
    import TriggerWindow from "./TriggerWindow.svelte";
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";
    import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";
    import type {PromptTriggerWindowProps} from "./prompt";
    import type {EmailSearchResult, MatchableText} from "./OvermailPromptViewModel.svelte";
    import {_} from "svelte-i18n";

    let {
        query,
        left,
        bottom,
        viewModel,
        onReplace,
        onDismiss: _onDismiss,
    }: PromptTriggerWindowProps = $props();

    let emails: EmailSearchResult[] = $state([]);
    let highlightedIndex = $state(0);

    // Das Highlight muss sichtbar bleiben, sobald es sich aendert -- auch wenn die Liste
    // laenger ist als das Fenster. Gescrollt wird ausschliesslich der Listen-Container per
    // scrollTop; scrollIntoView wuerde stattdessen das Fenster/Popover verschieben.
    let listElement: HTMLElement | undefined = $state();
    let itemElements: (HTMLElement | undefined)[] = $state([]);

    $effect(() => {
        const item = itemElements[highlightedIndex];
        if (item && listElement) scrollIntoViewWithin(item, listElement);
    });

    type EmailBody = {text: string | null; html: string | null};

    const bodyRepository = new EmailBodyRepository();
    // Beim Navigieren mit ↑/↓ nicht jedes Mal neu laden.
    const bodyCache = new Map<string, EmailBody>();

    /** Breite, auf der HTML-Mails typischerweise designt sind; darauf wird gerendert. */
    const PREVIEW_DESIGN_WIDTH = 640;
    /** Innenabstand (p-2) der Vorschau-Ebene, geht von der nutzbaren Breite ab. */
    const PREVIEW_PADDING = 8;
    /** Obergrenze gleichzeitig gemounteter Vorschau-Iframes. */
    const MAX_MOUNTED_PREVIEWS = 20;
    /** Vergrößerungsfaktor der Hover-Lupe, relativ zur angezeigten Vorschau. */
    const LENS_MAGNIFICATION = 2;
    const LENS_RADIUS = 120;

    let previewWidth = $state(0);
    // `zoom` skaliert anders als `transform` auch das Layout mit — kein Spacer/Messen nötig.
    // Dynamisch, damit die Mail die volle Panelbreite ausfüllt.
    let previewZoom = $derived(
        previewWidth > 0 ? (previewWidth - 2 * PREVIEW_PADDING) / PREVIEW_DESIGN_WIDTH : 0.45
    );

    /**
     * Cursorposition der Lupe, null wenn keine aktiv. `panel*` platziert den Kreis im Panel,
     * `content*` zeigt auf denselben Punkt im (ggf. gescrollten) Inhalt.
     */
    let lens: {panelX: number; panelY: number; contentX: number; contentY: number} | null = $state(null);

    function onPreviewMousemove(event: MouseEvent) {
        const layer = event.currentTarget as HTMLElement;
        const rect = layer.getBoundingClientRect();
        const panelX = event.clientX - rect.left;
        const panelY = event.clientY - rect.top;
        lens = {
            panelX,
            panelY,
            contentX: panelX - PREVIEW_PADDING + layer.scrollLeft,
            contentY: panelY - PREVIEW_PADDING + layer.scrollTop,
        };
    }

    // Einmal geladene Vorschauen bleiben (unsichtbar) gemountet: der Wechsel ist dann nur
    // ein visibility-Toggle. Würde das iframe pro Wechsel neu gerendert, parst es sein
    // srcdoc neu und lädt alle Bilder nach — das war die spürbare Verzögerung trotz Cache.
    let mountedPreviews: {id: string; body: EmailBody}[] = $state([]);

    let currentEmailId = $derived(emails[highlightedIndex]?.id ?? null);

    $effect(() => {
        void currentEmailId;
        lens = null;
    });
    let previewLoading = $derived(
        currentEmailId !== null && !mountedPreviews.some((entry) => entry.id === currentEmailId)
    );

    function mountPreview(id: string) {
        if (mountedPreviews.some((entry) => entry.id === id)) return;
        const body = bodyCache.get(id);
        if (!body) return;
        mountedPreviews.push({id, body});
        if (mountedPreviews.length > MAX_MOUNTED_PREVIEWS) mountedPreviews.shift();
    }

    $effect(() => {
        const email = emails[highlightedIndex];
        if (!email) return;

        if (bodyCache.has(email.id)) {
            mountPreview(email.id);
            return;
        }

        bodyRepository.getBody(email.id)
            .then((body) => {
                bodyCache.set(email.id, body);
                mountPreview(email.id);
            })
            .catch(() => {
                bodyCache.set(email.id, {text: null, html: null});
                mountPreview(email.id);
            });
    });

    $effect(() => {
        const current = query;
        viewModel.findEmails(current).then((result) => {
            if (current !== query) return; // veraltete Antwort
            emails = result;
            highlightedIndex = 0;
        });
    });

    // Zerlegt einen Text anhand seiner Match-Ranges (end exklusiv) in normale und
    // hervorgehobene Abschnitte.
    function matchParts(matchable: MatchableText): {text: string; matched: boolean}[] {
        const parts: {text: string; matched: boolean}[] = [];
        let position = 0;
        for (const match of matchable.matches) {
            if (match.start > position) parts.push({text: matchable.text.slice(position, match.start), matched: false});
            parts.push({text: matchable.text.slice(match.start, match.end), matched: true});
            position = match.end;
        }
        if (position < matchable.text.length) parts.push({text: matchable.text.slice(position), matched: false});
        return parts;
    }

    function select(email: EmailSearchResult | undefined) {
        if (!email) return;
        onReplace({type: "email", email: {id: email.id, subject: email.subject.text, avatarUrl: email.avatarUrl}});
    }

    // Vom PromptInput weitergereichte Tastatur-Events; true = Event verbraucht.
    export function handleKey(event: KeyboardEvent): boolean {
        if (event.key === "ArrowDown" && emails.length > 0) {
            highlightedIndex = (highlightedIndex + 1) % emails.length;
            return true;
        }
        if (event.key === "ArrowUp" && emails.length > 0) {
            highlightedIndex = (highlightedIndex - 1 + emails.length) % emails.length;
            return true;
        }
        if (event.key === "Enter" && !event.shiftKey) {
            select(emails[highlightedIndex]);
            return true;
        }
        return false;
    }
</script>

<TriggerWindow {left} {bottom} class="w-max min-w-80 max-w-[min(28rem,calc(100vw-2rem))] max-h-[38rem] p-1">
    {#if currentEmailId !== null}
        <!-- Vorschau des präferierten Inhalts (HTML vor Text) der markierten Mail. Alle schon
             geladenen Vorschauen bleiben als unsichtbare Ebenen gemountet (invisible statt
             hidden, damit Layout und ResizeObserver im iframe weiterlaufen). -->
        <div class="relative mb-1 h-80 overflow-hidden rounded-sm border bg-background" bind:clientWidth={previewWidth}>
            {#if previewLoading}
                <div class="flex h-full items-center justify-center">
                    <Spinner/>
                </div>
            {/if}

            {#each mountedPreviews as entry (entry.id)}
                <div
                        class={cn(
                            "absolute inset-0 cursor-none overflow-y-auto overflow-x-hidden p-2",
                            entry.id !== currentEmailId && "invisible",
                        )}
                        onmousemove={onPreviewMousemove}
                        onmouseleave={() => lens = null}
                        role="presentation"
                >
                    <!-- pointer-events-none: die Vorschau ist wirklich nur eine Vorschau. Ohne
                         das schluckt das iframe die mousemove-Events und die Lupe bleibt stehen. -->
                    <div class="pointer-events-none">
                        {#if entry.body.html}
                            <!-- Feste Designbreite, dynamischer Zoom auf die Panelbreite —
                                 volle Breite, nie horizontal scrollen. -->
                            <div style:zoom={previewZoom} style:width="{PREVIEW_DESIGN_WIDTH}px">
                                <EmailHtmlBody html={entry.body.html}/>
                            </div>
                        {:else if entry.body.text}
                            <pre class="whitespace-pre-wrap font-sans text-xs">{entry.body.text}</pre>
                        {:else}
                            <div class="flex h-64 items-center justify-center text-xs text-muted-foreground">
                                {$_('ai.emails.noPreview')}
                            </div>
                        {/if}
                    </div>
                </div>
            {/each}

            <!-- Die Lupen liegen ausserhalb der Scroll-Ebenen: innerhalb wuerden sie am Rand
                 den Scrollbereich vergroessern. Positioniert wird in Panel-Koordinaten, der
                 Inhalt wird um die Scrollposition der Ebene verschoben. Pro Vorschau eine
                 gemountete Kopie, damit beim Hovern nichts nachlaedt. -->
            {#each mountedPreviews as entry (entry.id)}
                <div
                        class={cn(
                            "pointer-events-none absolute z-10 overflow-hidden rounded-full border-2 bg-background shadow-lg",
                            (lens === null || entry.id !== currentEmailId) && "invisible",
                        )}
                        style:width="{LENS_RADIUS * 2}px"
                        style:height="{LENS_RADIUS * 2}px"
                        style:left="{(lens?.panelX ?? 0) - LENS_RADIUS}px"
                        style:top="{(lens?.panelY ?? 0) - LENS_RADIUS}px"
                >
                    <div
                            class="absolute"
                            style:left="{LENS_RADIUS - (lens?.contentX ?? 0) * LENS_MAGNIFICATION}px"
                            style:top="{LENS_RADIUS - (lens?.contentY ?? 0) * LENS_MAGNIFICATION}px"
                    >
                        {#if entry.body.html}
                            <div style:zoom={previewZoom * LENS_MAGNIFICATION} style:width="{PREVIEW_DESIGN_WIDTH}px">
                                <EmailHtmlBody html={entry.body.html}/>
                            </div>
                        {:else if entry.body.text}
                            <pre
                                    class="whitespace-pre-wrap font-sans text-xs"
                                    style:zoom={LENS_MAGNIFICATION}
                                    style:width="{previewWidth - 2 * PREVIEW_PADDING}px"
                            >{entry.body.text}</pre>
                        {/if}
                    </div>
                </div>
            {/each}
        </div>
    {/if}

    {#if emails.length === 0}
        <div class="px-2 py-1.5 text-muted-foreground">{$_('ai.emails.empty')}</div>
    {/if}

    <div bind:this={listElement} class="max-h-56 overflow-y-auto">
        {#each emails as email, index}
            <button
                    bind:this={itemElements[index]}
                    type="button"
                    class={cn(
                        "flex w-full flex-row items-center gap-2 rounded-sm px-2 py-1.5 text-left",
                        index === highlightedIndex && "bg-accent text-accent-foreground",
                    )}
                    onmousedown={(event) => {
                        // preventDefault: der Fokus muss im Prompt-Editor bleiben.
                        event.preventDefault();
                        highlightedIndex = index;
                    }}
                    ondblclick={() => select(email)}
            >
                <Avatar.Root size="lg">
                    {#if email.avatarUrl}
                        <Avatar.Image src={email.avatarUrl} alt=""/>
                    {/if}
                    <Avatar.Fallback class="text-base">{initials(email.from.name?.text ?? email.from.address.text)}</Avatar.Fallback>
                </Avatar.Root>
                <span class="flex min-w-0 flex-1 flex-col gap-0.5">
                    <span class="w-full truncate">
                        {#each matchParts(email.subject) as part}
                            {#if part.matched}<span class="font-semibold">{part.text}</span>{:else}{part.text}{/if}
                        {/each}
                    </span>
                    <span class="flex w-full items-center gap-1.5 text-xs text-muted-foreground">
                        <span class="truncate">
                            {#if email.from.name}
                                {#each matchParts(email.from.name) as part}
                                    {#if part.matched}<span class="font-semibold text-foreground">{part.text}</span>{:else}{part.text}{/if}
                                {/each}
                                <span> · </span>
                            {/if}
                            {#each matchParts(email.from.address) as part}
                                {#if part.matched}<span class="font-semibold text-foreground">{part.text}</span>{:else}{part.text}{/if}
                            {/each}
                        </span>
                        <span class="ms-auto shrink-0">{new Date(email.date).toLocaleDateString()}</span>
                    </span>
                </span>
            </button>
        {/each}
    </div>
</TriggerWindow>
