<!--
    The switcher's chat list: grouped by date, pages in as you scroll and keeps only the visible
    rows in the DOM.

    A hand-rolled listbox rather than Select.Item: with windowing, a select primitive only knows
    the items currently rendered, so arrow keys and typeahead would stop at the window edge. That
    is why focus stays on the container here and the active row hangs off aria-activedescendant.
-->
<script lang="ts">
    import {onMount} from "svelte";
    import {differenceInCalendarDays, formatDistance} from "date-fns";
    import {de, enUS} from "date-fns/locale";
    import {_, locale} from "svelte-i18n";
    import {Spinner} from "$lib/components/ui/spinner";
    import type {AiChat, AiChatViewModel} from "$lib/app/ai/AiChatViewModel.svelte";

    let {viewModel, onSelect}: {
        viewModel: AiChatViewModel,
        onSelect: (chatId: string) => void,
    } = $props();

    // Fixed heights, because the windowing has to compute every row's position without measuring.
    // They are written onto the rows as an inline style, so model and DOM cannot drift apart.
    const ROW_HEIGHT = 54;
    const HEADING_HEIGHT = 28;
    const GROUP_GAP = 8;

    /** Rows above and below the window, so scrolling does not run past empty space. */
    const OVERSCAN = 4 * ROW_HEIGHT;

    /** How far before the end the next page is requested. */
    const LOAD_MORE_THRESHOLD = 3 * ROW_HEIGHT;

    /** After this pause a keystroke starts a new search instead of appending to the current one. */
    const TYPEAHEAD_RESET_MS = 600;

    // date-fns has catalogs of its own, so the relative dates have to be pointed at the ui
    // language separately. `$locale` can be a regional tag such as `de-DE`.
    const dateLocale = $derived($locale?.slice(0, 2) === "de" ? de : enUS);

    // "5 minutes ago" goes stale while the list is open. Every date is computed against this
    // value rather than Date.now(), so labels and grouping move on together.
    let now = $state(new Date());
    onMount(() => {
        const interval = setInterval(() => now = new Date(), 60_000);
        return () => clearInterval(interval);
    });

    const groupKeys = ["today", "yesterday", "lastWeek", "lastMonth", "older"] as const;

    // Calendar days, not elapsed hours: a chat from 23:00 is "yesterday" at 01:00.
    function groupKeyOf(chat: AiChat): (typeof groupKeys)[number] {
        const days = differenceInCalendarDays(now, chat.created_at);
        if (days <= 0) return "today";
        if (days === 1) return "yesterday";
        if (days <= 7) return "lastWeek";
        if (days <= 30) return "lastMonth";
        return "older";
    }

    type Row =
        | {kind: "heading", domId: string, labelKey: string, top: number, height: number}
        | {kind: "chat", domId: string, chat: AiChat, top: number, height: number};

    // Groups and rows in one flat list with a precomputed position -- both the windowing and the
    // arrow-key navigation build on that.
    const rows: Row[] = $derived.by(() => {
        const grouped = groupKeys
            .map((key) => ({
                key,
                chats: viewModel.chatsNewestFirst.filter((chat) => groupKeyOf(chat) === key),
            }))
            .filter((group) => group.chats.length > 0);

        const out: Row[] = [];
        let top = 0;

        grouped.forEach((group, index) => {
            if (index > 0) top += GROUP_GAP;

            out.push({
                kind: "heading",
                domId: `ai-chat-group-${group.key}`,
                labelKey: `ai.chat.chats.groups.${group.key}`,
                top,
                height: HEADING_HEIGHT,
            });
            top += HEADING_HEIGHT;

            group.chats.forEach((chat) => {
                out.push({kind: "chat", domId: `ai-chat-row-${chat.id}`, chat, top, height: ROW_HEIGHT});
                top += ROW_HEIGHT;
            });
        });

        return out;
    });

    const totalHeight = $derived(
        rows.length === 0 ? 0 : rows[rows.length - 1].top + rows[rows.length - 1].height
    );

    let scrollTop = $state(0);
    let viewportHeight = $state(0);
    let listElement: HTMLDivElement | undefined = $state();

    /** Indices of the chat rows within [rows]; the headings are skipped. */
    const chatRowIndices = $derived(
        rows.reduce<number[]>((acc, row, index) => {
            if (row.kind === "chat") acc.push(index);
            return acc;
        }, [])
    );

    let activeRowIndex: number | null = $state(null);
    const activeRow = $derived(activeRowIndex === null ? null : rows[activeRowIndex] ?? null);

    const windowedRows = $derived(
        rows.filter((row) =>
            row.top + row.height > scrollTop - OVERSCAN && row.top < scrollTop + viewportHeight + OVERSCAN
        )
    );

    // The active row stays rendered even once it is scrolled out of the window:
    // aria-activedescendant must not point at an element that no longer exists. The order does
    // not matter, the rows are positioned absolutely.
    const renderedRows = $derived(
        activeRow !== null && !windowedRows.includes(activeRow)
            ? [...windowedRows, activeRow]
            : windowedRows
    );

    // Covers both cases: scrolled close to the end, and content shorter than the window (nothing
    // to scroll, yet there is more to come). Repeat requests are caught by the view model.
    $effect(() => {
        if (!viewModel.hasMoreChats) return;
        if (totalHeight - (scrollTop + viewportHeight) > LOAD_MORE_THRESHOLD) return;

        viewModel.loadMoreChats();
    });

    function scrollRowIntoView(row: Row) {
        if (!listElement) return;

        const paddingTop = 6;
        if (row.top < scrollTop) {
            listElement.scrollTop = row.top;
        } else if (row.top + row.height > scrollTop + viewportHeight) {
            listElement.scrollTop = row.top + row.height - viewportHeight + paddingTop;
        }
    }

    function moveActive(direction: 1 | -1) {
        if (chatRowIndices.length === 0) return;

        const current = activeRowIndex === null ? -1 : chatRowIndices.indexOf(activeRowIndex);
        // From nowhere, arrow-down starts at the top and arrow-up at the bottom.
        const next = current === -1
            ? (direction === 1 ? 0 : chatRowIndices.length - 1)
            : Math.min(Math.max(current + direction, 0), chatRowIndices.length - 1);

        activeRowIndex = chatRowIndices[next];
        if (activeRow) scrollRowIntoView(activeRow);
    }

    function setActiveTo(position: "first" | "last") {
        if (chatRowIndices.length === 0) return;

        activeRowIndex = position === "first"
            ? chatRowIndices[0]
            : chatRowIndices[chatRowIndices.length - 1];
        if (activeRow) scrollRowIntoView(activeRow);
    }

    let typeahead = $state("");
    let typeaheadTimeout: ReturnType<typeof setTimeout> | undefined;

    function runTypeahead(character: string) {
        clearTimeout(typeaheadTimeout);
        typeahead += character.toLowerCase();
        typeaheadTimeout = setTimeout(() => typeahead = "", TYPEAHEAD_RESET_MS);

        // Search on from the active row, so repeating an initial letter walks through the
        // matches instead of sticking to the first one.
        const start = activeRowIndex === null ? 0 : chatRowIndices.indexOf(activeRowIndex) + 1;
        const order = [
            ...chatRowIndices.slice(start),
            ...chatRowIndices.slice(0, Math.max(start, 0)),
        ];

        const hit = order.find((index) => {
            const row = rows[index];
            return row.kind === "chat" && titleOf(row.chat).toLowerCase().startsWith(typeahead);
        });

        if (hit === undefined) return;

        activeRowIndex = hit;
        if (activeRow) scrollRowIntoView(activeRow);
    }

    function onKeyDown(event: KeyboardEvent) {
        switch (event.key) {
            case "ArrowDown":
                event.preventDefault();
                moveActive(1);
                return;
            case "ArrowUp":
                event.preventDefault();
                moveActive(-1);
                return;
            case "Home":
                event.preventDefault();
                setActiveTo("first");
                return;
            case "End":
                event.preventDefault();
                setActiveTo("last");
                return;
            case "Enter":
            case " ":
                if (activeRow?.kind !== "chat") return;
                event.preventDefault();
                onSelect(activeRow.chat.id);
                return;
        }

        // Printable single characters only, so Tab, Escape and shortcuts get through -- Escape
        // closes the popover, which is not this component's business.
        if (event.key.length === 1 && !event.metaKey && !event.ctrlKey && !event.altKey) {
            event.preventDefault();
            runTypeahead(event.key);
        }
    }

    /** Null until the model has named the chat; that happens after the first message. */
    const titleOf = (chat: AiChat) => chat.name ?? $_('ai.chat.chats.untitled');

    export function focusList() {
        listElement?.focus();
        // The current selection is the natural starting point for the keyboard.
        const selected = rows.findIndex(
            (row) => row.kind === "chat" && row.chat.id === viewModel.currentChatId
        );
        if (selected !== -1) {
            activeRowIndex = selected;
            scrollRowIntoView(rows[selected]);
        }
    }
</script>

<div class="relative">
    <div
        bind:this={listElement}
        bind:clientHeight={viewportHeight}
        onscroll={(event) => scrollTop = event.currentTarget.scrollTop}
        onkeydown={onKeyDown}
        role="listbox"
        tabindex="0"
        aria-label={$_('ai.chat.history')}
        aria-activedescendant={activeRow?.domId ?? undefined}
        class="max-h-104 overflow-y-auto overscroll-contain py-1.5 outline-none"
    >
        {#if rows.length === 0}
            <p class="px-4.5 py-8 text-center text-sm text-muted-foreground">
                {viewModel.isLoadingChats ? $_('ai.chat.chats.loading') : $_('ai.chat.chats.empty')}
            </p>
        {:else}
            <!-- Carries the full height of all rows, so the scrollbar is right even though only
                 the window is rendered. -->
            <!-- role=presentation on the wrappers: a listbox has to own its options, and generic
                 divs in between would break that relationship. -->
            <div role="presentation" class="relative" style="height: {totalHeight}px">
                {#each renderedRows as row (row.domId)}
                    <div
                            role="presentation"
                            class="absolute inset-x-0"
                            style="top: {row.top}px; height: {row.height}px"
                    >
                        {#if row.kind === "heading"}
                            <div class="flex h-full items-end px-4.5 pb-1 text-xs font-medium text-muted-foreground">
                                {$_(row.labelKey)}
                            </div>
                        {:else}
                            {@const chat = row.chat}
                            {@const isSelected = chat.id === viewModel.currentChatId}
                            <!-- No tabindex and no key handler of its own: with the
                                 aria-activedescendant pattern the focus stays on the listbox,
                                 which also handles the keyboard. That is what the linter flags
                                 here. -->
                            <!-- svelte-ignore a11y_interactive_supports_focus -->
                            <!-- svelte-ignore a11y_click_events_have_key_events -->
                            <div
                                    id={row.domId}
                                    role="option"
                                    aria-selected={isSelected}
                                    onclick={() => onSelect(chat.id)}
                                    onmouseenter={() => viewModel.onChatHovered(chat.id)}
                                    class="mx-1.5 flex h-full cursor-default flex-col justify-center gap-0.5
                                           rounded-2xl px-3 text-sm font-medium
                                           {row.domId === activeRow?.domId ? 'bg-foreground/10' : ''}
                                           {isSelected && row.domId !== activeRow?.domId ? 'bg-foreground/5' : ''}
                                           hover:bg-foreground/10"
                            >
                                <span class="truncate {chat.name ? '' : 'text-muted-foreground'}">
                                    {titleOf(chat)}
                                </span>
                                <span class="truncate text-xs font-normal text-muted-foreground">
                                    {formatDistance(chat.created_at, now, {addSuffix: true, locale: dateLocale})}
                                </span>
                            </div>
                        {/if}
                    </div>
                {/each}
            </div>
        {/if}
    </div>

    <!-- An overlay rather than a bar of its own: the popover's height must not change while a
         page loads. The gradient fades out the rows underneath, so the spinner stays legible. -->
    {#if viewModel.isLoadingChats && rows.length > 0}
        <div class="pointer-events-none absolute inset-x-0 bottom-0 flex justify-center
                    bg-gradient-to-t from-popover to-transparent pt-6 pb-2">
            <Spinner class="size-4 text-muted-foreground"/>
        </div>
    {/if}
</div>
