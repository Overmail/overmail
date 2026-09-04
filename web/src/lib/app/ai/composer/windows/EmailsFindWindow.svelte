<script lang="ts">
    import {OvermailAvatar} from "$lib/components/avatar";
    import {Spinner} from "$lib/components/ui/spinner";
    import {cn, scrollIntoViewWithin} from "$lib/utils.js";
    import TriggerWindow from "$lib/app/ai/composer/windows/TriggerWindow.svelte";
    import EmailBodyPreview from "$lib/app/mails/EmailBodyPreview.svelte";
    import {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";
    import type {PromptTriggerWindowProps} from "$lib/app/ai/composer/prompt";
    import type {EmailSearchResult, MatchableText} from "$lib/app/ai/composer/OvermailPromptViewModel.svelte";
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

    /** Upper bound on preview iframes mounted at the same time. */
    const MAX_MOUNTED_PREVIEWS = 20;

    // Previews stay mounted (invisibly) once loaded, so switching is only a visibility toggle.
    // Re-rendering the iframe on every switch would make it parse its srcdoc again and refetch
    // all images -- that was the noticeable delay despite the cache.
    let mountedPreviews: {id: string; body: EmailBody}[] = $state([]);

    let currentEmailId = $derived(emails[highlightedIndex]?.id ?? null);

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
        onReplace({
            type: "email",
            email: {
                id: email.id,
                subject: email.subject.text,
                avatarUrl: email.avatarUrl,
                avatarPadding: email.avatarPadding,
            },
        });
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
        <div class="relative mb-1 h-80 overflow-hidden rounded-sm border bg-background">
            {#if previewLoading}
                <div class="flex h-full items-center justify-center">
                    <Spinner/>
                </div>
            {/if}

            {#each mountedPreviews as entry (entry.id)}
                <EmailBodyPreview
                        body={entry.body}
                        active={entry.id === currentEmailId}
                        class={cn("absolute inset-0", entry.id !== currentEmailId && "invisible")}
                />
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
                <OvermailAvatar
                        size="lg"
                        url={email.avatarUrl}
                        name={email.from.name?.text ?? email.from.address.text}
                />
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
