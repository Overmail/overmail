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

    // The highlight has to stay visible as it moves, even when the list is taller than the
    // window. Only the list container is scrolled, via scrollTop; scrollIntoView would move
    // the window or the popover instead.
    let listElement: HTMLElement | undefined = $state();
    let itemElements: (HTMLElement | undefined)[] = $state([]);

    $effect(() => {
        const item = itemElements[highlightedIndex];
        if (item && listElement) scrollIntoViewWithin(item, listElement);
    });

    type EmailBody = {text: string | null; html: string | null};

    const bodyRepository = new EmailBodyRepository();
    // So that navigating with up/down does not refetch every time.
    const bodyCache = new Map<string, EmailBody>();

    /** The width html mails are typically designed for; that is what they are rendered at. */
    const PREVIEW_DESIGN_WIDTH = 640;
    /** Padding of the preview layer; comes off the usable width. */
    const PREVIEW_PADDING = 0;
    /** Upper bound on preview iframes mounted at the same time. */
    const MAX_MOUNTED_PREVIEWS = 20;
    /** Magnification of the hover lens, relative to the preview as displayed. */
    const LENS_MAGNIFICATION = 2;
    const LENS_RADIUS = 120;

    let previewWidth = $state(0);
    // Unlike `transform`, `zoom` scales the layout along with it -- no spacer and no measuring
    // needed. Dynamic, so the mail fills the full panel width.
    let previewZoom = $derived(
        previewWidth > 0 ? (previewWidth - 2 * PREVIEW_PADDING) / PREVIEW_DESIGN_WIDTH : 0.45
    );

    /**
     * Cursor position of the lens, null when none is active. `panel*` places the circle in the
     * panel, `content*` points at the same spot in the (possibly scrolled) content.
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

    // Previews stay mounted (invisibly) once loaded, so switching is only a visibility toggle.
    // Re-rendering the iframe on every switch would make it parse its srcdoc again and refetch
    // all images -- that was the noticeable delay despite the cache.
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
            if (current !== query) return; // stale response
            emails = result;
            highlightedIndex = 0;
        });
    });

    // Splits a text into plain and highlighted parts along its match ranges (end exclusive).
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

    // Keyboard events handed down by PromptInput; true means the event was consumed.
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

<TriggerWindow {left} {bottom} class="w-max min-w-80 max-w-[min(28rem,calc(100vw-2rem))] max-h-152 p-1">
    {#if currentEmailId !== null}
        <!-- Preview of the highlighted mail's preferred content (html over text). Every preview
             already loaded stays mounted as an invisible layer (invisible rather than hidden, so
             layout and the ResizeObserver inside the iframe keep working). -->
        <div class="relative mb-1 h-80 overflow-hidden rounded-sm border bg-background" bind:clientWidth={previewWidth}>
            {#if previewLoading}
                <div class="flex h-full items-center justify-center">
                    <Spinner/>
                </div>
            {/if}

            {#each mountedPreviews as entry (entry.id)}
                <div
                        class={cn(
                            "absolute inset-0 cursor-none overflow-y-auto overflow-x-hidden",
                            entry.id !== currentEmailId && "invisible",
                            entry.body.text && !entry.body.html && "p-2",
                        )}
                        onmousemove={onPreviewMousemove}
                        onmouseleave={() => lens = null}
                        role="presentation"
                >
                    <!-- pointer-events-none: the preview really is only a preview. Without it the
                         iframe swallows the mousemove events and the lens freezes. -->
                    <div class="pointer-events-none">
                        {#if entry.body.html}
                            <!-- Fixed design width, zoom scaled to the panel width: full width,
                                 never a horizontal scrollbar. -->
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

            <!-- The lenses sit outside the scroll layers: inside, they would grow the scroll
                 area at the edges. They are positioned in panel coordinates and their content is
                 offset by the layer's scroll position. One mounted copy per preview, so hovering
                 never triggers a load. -->
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

    <div bind:this={listElement} class="max-h-56 overflow-y-auto pb-12">
        {#each emails as email, index}
            <button
                    bind:this={itemElements[index]}
                    type="button"
                    class={cn(
                        "flex w-full flex-row items-center gap-2 rounded-sm px-2 py-1.5 text-left",
                        index === highlightedIndex && "bg-accent text-accent-foreground",
                    )}
                    onmousedown={(event) => {
                        // preventDefault: the focus has to stay in the prompt editor.
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
