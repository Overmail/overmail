<!--
    Die Chat-Liste des Switchers: nach Datum gruppiert, lädt beim Scrollen nach und hält nur die
    sichtbaren Zeilen im DOM.

    Eigene Listbox statt Select.Item: bei Windowing kennt eine Select-Primitive nur die gerade
    gerenderten Items, Pfeiltasten und Typeahead würden am Fensterrand abbrechen. Deshalb liegt
    der Fokus hier immer auf dem Container und die aktive Zeile hängt an aria-activedescendant.
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

    // Feste Höhen, weil das Windowing die Position jeder Zeile ohne Messung ausrechnen muss.
    // Sie stehen als inline style an den Zeilen, damit Modell und DOM nicht auseinanderlaufen
    // können.
    const ROW_HEIGHT = 54;
    const HEADING_HEIGHT = 28;
    const GROUP_GAP = 8;

    /** Zeilen über dem und unter dem Fenster, damit Scrollen nicht an leeren Flächen vorbeiläuft. */
    const OVERSCAN = 4 * ROW_HEIGHT;

    /** So weit vor dem Ende wird die nächste Seite angefragt. */
    const LOAD_MORE_THRESHOLD = 3 * ROW_HEIGHT;

    /** Nach dieser Pause fängt eine Tasteneingabe eine neue Suche an, statt anzuhängen. */
    const TYPEAHEAD_RESET_MS = 600;

    // date-fns hat eigene Kataloge, die relativen Daten müssen also separat auf die
    // UI-Sprache gezeigt werden. `$locale` kann ein Regionaltag wie `de-DE` sein.
    const dateLocale = $derived($locale?.slice(0, 2) === "de" ? de : enUS);

    // "vor 5 Minuten" veraltet, während die Liste offen ist. Alle Daten werden gegen diesen Wert
    // gerechnet statt gegen Date.now(), damit Labels und Gruppierung zusammen weiterlaufen.
    let now = $state(new Date());
    onMount(() => {
        const interval = setInterval(() => now = new Date(), 60_000);
        return () => clearInterval(interval);
    });

    const groupKeys = ["today", "yesterday", "lastWeek", "lastMonth", "older"] as const;

    // Kalendertage, nicht verstrichene Stunden: ein Chat von 23:00 ist um 01:00 "gestern".
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

    // Gruppen und Zeilen in einer flachen Liste mit ausgerechneter Position -- darauf beruht
    // sowohl das Windowing als auch die Pfeiltasten-Navigation.
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

    /** Indizes der Chat-Zeilen in [rows]; die Überschriften werden übersprungen. */
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

    // Die aktive Zeile bleibt gerendert, auch wenn sie aus dem Fenster gescrollt wurde:
    // aria-activedescendant darf nicht auf ein Element zeigen, das es nicht mehr gibt. Die
    // Reihenfolge ist egal, die Zeilen sind absolut positioniert.
    const renderedRows = $derived(
        activeRow !== null && !windowedRows.includes(activeRow)
            ? [...windowedRows, activeRow]
            : windowedRows
    );

    // Deckt beides ab: nah am Ende gescrollt, und Inhalt kürzer als das Fenster (dann kann man
    // gar nicht scrollen, es gibt aber noch mehr). Die Mehrfachanfrage fängt das ViewModel ab.
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
        // Von nirgendwo aus startet Pfeil-runter oben und Pfeil-hoch unten.
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

        // Ab der aktiven Zeile weitersuchen, damit wiederholte Anfangsbuchstaben durch die
        // Treffer wandern statt am ersten zu kleben.
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

        // Nur druckbare Einzelzeichen, damit Tab, Escape und Shortcuts durchkommen -- Escape
        // schließt das Popover, das gehört nicht hierher.
        if (event.key.length === 1 && !event.metaKey && !event.ctrlKey && !event.altKey) {
            event.preventDefault();
            runTypeahead(event.key);
        }
    }

    /** Null, bis das Modell den Chat benannt hat; das passiert nach der ersten Nachricht. */
    const titleOf = (chat: AiChat) => chat.name ?? $_('ai.chat.chats.untitled');

    export function focusList() {
        listElement?.focus();
        // Die Auswahl ist der natürliche Startpunkt für die Tastatur.
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
            <!-- Trägt die volle Höhe aller Zeilen, damit die Scrollbar stimmt, obwohl nur das
                 Fenster gerendert ist. -->
            <!-- role=presentation auf den Wrappern: eine Listbox muss ihre Optionen besitzen,
                 dazwischenliegende generische Divs würden diese Beziehung aufbrechen. -->
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
                            <!-- Kein tabindex und kein eigener Key-Handler: beim
                                 aria-activedescendant-Muster bleibt der Fokus auf der Listbox, die
                                 auch die Tastatur auswertet. Genau das meldet der Linter hier an. -->
                            <!-- svelte-ignore a11y_interactive_supports_focus -->
                            <!-- svelte-ignore a11y_click_events_have_key_events -->
                            <div
                                    id={row.domId}
                                    role="option"
                                    aria-selected={isSelected}
                                    onclick={() => onSelect(chat.id)}
                                    class="mx-1.5 flex h-full cursor-default flex-col justify-center gap-0.5
                                           rounded-2xl px-3 text-sm font-medium
                                           {row.domId === activeRow?.domId ? 'bg-foreground/10' : ''}
                                           {isSelected && row.domId !== activeRow?.domId ? 'bg-foreground/5' : ''}
                                           hover:bg-foreground/10"
                            >
                                <span class="truncate {chat.name ? '' : 'text-muted-foreground italic'}">
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

    <!-- Overlay statt eigener Leiste: beim Nachladen soll die Höhe des Popovers gleich bleiben.
         Der Verlauf blendet die Zeilen darunter weg, damit der Spinner lesbar bleibt. -->
    {#if viewModel.isLoadingChats && rows.length > 0}
        <div class="pointer-events-none absolute inset-x-0 bottom-0 flex justify-center
                    bg-gradient-to-t from-popover to-transparent pt-6 pb-2">
            <Spinner class="size-4 text-muted-foreground"/>
        </div>
    {/if}
</div>
