<!--
    The one preview the mail table has: the mail under the cursor, once the cursor has stayed on
    its row long enough to mean it.

    One instance for the whole table, driven by the rows through [hover] and [leave] -- so there is
    never a second card, and never one for a row the cursor has left. It cannot be reached with the
    mouse either: it follows the cursor on the x axis and is only ever in the way of the row it
    belongs to, which is why it does not take pointer events at all.
-->
<script lang="ts">
    import {fade, scale} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import EmailPreviewCard from "$lib/app/mails/EmailPreviewCard.svelte";
    import {previewPosition} from "$lib/app/mails/rowPreviewPosition";
    import {useRepositories} from "$lib/repository/repositories";

    /** How long the cursor has to stay on a row before its mail is worth showing. */
    const DELAY_MS = 1000;

    /** The card's width, which is what it is positioned by. Keep in step with `w-80` below. */
    const CARD_WIDTH = 320;

    /** Coming in it grows out of the row; going out it only fades, and faster. */
    const IN_MS = 140;
    const OUT_MS = 100;

    const {mails} = useRepositories();

    /** The row under the cursor and where on the x axis the cursor is on it. */
    let target: {id: string; element: HTMLElement; x: number} | null = $state(null);

    /** False while the delay is still running -- the row is hovered, the card is not up yet. */
    let isOpen = $state(false);

    /** Measured, because it decides whether the card goes below the row or above it. */
    let height = $state(0);

    /**
     * Bumped by anything that moves the row without moving the cursor. A rect is read, not
     * observed, so this is what tells the position below that it has to be read again.
     */
    let viewport = $state(0);

    let timer: ReturnType<typeof setTimeout> | null = null;

    function stopWaiting() {
        if (timer !== null) clearTimeout(timer);
        timer = null;
    }

    /**
     * Called by a row for every cursor move on it, with the id of the mail it holds.
     *
     * The same row moving the cursor along is not a new hover: the wait keeps running and the card
     * follows on the x axis. Another row starts over, which is what makes running down the list
     * show nothing at all.
     */
    export function hover(event: MouseEvent, id: string) {
        const element = event.currentTarget as HTMLElement;

        if (target?.element === element && target.id === id) {
            target = {...target, x: event.clientX};
            return;
        }

        stopWaiting();
        isOpen = false;
        target = {id, element, x: event.clientX};
        timer = setTimeout(() => (isOpen = true), DELAY_MS);
    }

    /** Called by a row the cursor leaves. The card goes with it, waiting or not. */
    export function leave() {
        stopWaiting();
        isOpen = false;
        target = null;
    }

    $effect(() => () => stopWaiting());

    // Only while a card is up: the list scrolls under a still cursor often enough, and then the
    // row it belongs to is somewhere else.
    $effect(() => {
        if (!isOpen) return;

        const bump = () => viewport++;
        window.addEventListener("scroll", bump, {passive: true});
        window.addEventListener("resize", bump);
        return () => {
            window.removeEventListener("scroll", bump);
            window.removeEventListener("resize", bump);
        };
    });

    const mail = $derived.by(() => {
        const current = target;
        return isOpen && current !== null ? mails.peek(current.id).value : null;
    });

    const position = $derived.by(() => {
        const current = target;
        if (current === null || mail === null) return null;

        void viewport;
        // The virtualizer recycles rows out of the DOM as the list scrolls; a detached one has no
        // rect to speak of, so the card has nothing left to sit next to.
        if (!current.element.isConnected) return null;

        const rect = current.element.getBoundingClientRect();

        return previewPosition({
            cursorX: current.x,
            rowTop: rect.top,
            rowBottom: rect.bottom,
            cardWidth: CARD_WIDTH,
            cardHeight: height,
            viewportWidth: window.innerWidth,
            viewportHeight: window.innerHeight,
        });
    });
</script>

{#if position !== null && mail !== null}
    <!-- pointer-events-none: the card sits between the cursor and the rest of the list, and
         nothing about it is there to be clicked -- the row underneath stays hovered, and leaving
         the row is what takes the card away. -->
    <div
            bind:clientHeight={height}
            class="pointer-events-none fixed z-50 w-80"
            style:left="{position.left}px"
            style:top="{position.top}px"
            style:transform-origin={position.placement === "below" ? "top center" : "bottom center"}
            in:scale={{duration: IN_MS, start: 0.97, opacity: 0, easing: cubicOut}}
            out:fade={{duration: OUT_MS}}
            aria-hidden="true"
    >
        <EmailPreviewCard {mail} class="shadow-2xl"/>
    </div>
{/if}
