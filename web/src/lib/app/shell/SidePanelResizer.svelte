<!-- The grip on the inline-start edge of the assistant panel, which drags its width. -->
<script lang="ts">
    import {_} from "svelte-i18n";
    import type {SidePanelState} from "$lib/app/shell/sidePanel.svelte";

    /** How far one arrow key press moves the edge -- the same step twice is a noticeable change. */
    const KEYBOARD_STEP = 24;

    let {
        panel,
        element,
    }: {
        panel: SidePanelState;
        /**
         * The panel being resized. Its inline-end edge is pinned to the window, so the distance
         * from there to the pointer is the width -- no offset from where the drag started to keep,
         * and nothing to drift out of sync with.
         */
        element: HTMLElement | undefined;
    } = $props();

    let handle: HTMLElement | undefined = $state();

    function widthAt(clientX: number): number {
        if (!element) return panel.width;

        const rect = element.getBoundingClientRect();
        return getComputedStyle(element).direction === "rtl"
            ? clientX - rect.left
            : rect.right - clientX;
    }

    function startResizing(event: PointerEvent) {
        if (event.button !== 0) return;

        // Without this the drag starts a text selection in the panel instead.
        event.preventDefault();
        // The pointer leaves the 8px grip in the first frame; capturing it keeps the moves coming
        // here rather than to whatever the cursor is over.
        handle?.setPointerCapture(event.pointerId);
        panel.isResizing = true;
    }

    function resize(event: PointerEvent) {
        if (!panel.isResizing) return;

        panel.width = widthAt(event.clientX);
    }

    function nudge(event: KeyboardEvent) {
        // Which arrow widens depends on the side the panel is on, like the drag above.
        const towardsStart = getComputedStyle(handle!).direction === "rtl" ? "ArrowRight" : "ArrowLeft";
        const towardsEnd = towardsStart === "ArrowLeft" ? "ArrowRight" : "ArrowLeft";

        if (event.key === towardsStart) {
            panel.width += KEYBOARD_STEP;
        } else if (event.key === towardsEnd) {
            panel.width -= KEYBOARD_STEP;
        } else {
            return;
        }

        // The arrows would scroll the page underneath as well.
        event.preventDefault();
    }

    // The cursor is over the page for most of the drag: without this it turns back into a text
    // cursor the moment it leaves the grip, and the page selects text under it.
    $effect(() => {
        if (!panel.isResizing) return;

        const {cursor, userSelect} = document.body.style;
        document.body.style.cursor = "col-resize";
        document.body.style.userSelect = "none";

        return () => {
            document.body.style.cursor = cursor;
            document.body.style.userSelect = userSelect;
        };
    });
</script>

<svelte:window onresize={() => panel.reclamp()} />

<!-- Reaches past the border on both sides, so it can be grabbed without aiming for one pixel. -->
<!-- A separator that can be moved is focusable and takes the arrow keys by definition; the rule
     only knows the static kind. -->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
        bind:this={handle}
        role="separator"
        aria-orientation="vertical"
        aria-label={$_('app.panel.resize')}
        aria-valuenow={Math.round(panel.width)}
        aria-valuemin={panel.minWidth}
        aria-valuemax={Math.round(panel.maxWidth)}
        tabindex="0"
        class={[
            "absolute inset-y-0 start-0 z-20 -ms-1 w-2 cursor-col-resize transition-colors",
            "hover:bg-blue-500/30 focus-visible:bg-blue-500/40 outline-none",
            panel.isResizing && "bg-blue-500/50",
        ]}
        onpointerdown={startResizing}
        onpointermove={resize}
        onpointerup={() => panel.isResizing = false}
        onlostpointercapture={() => panel.isResizing = false}
        onkeydown={nudge}
></div>
