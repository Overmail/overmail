<script lang="ts">
    // The card in the corner: it owns the surface, the socket and whether the panel stands open.
    //
    // One box that grows, not two that trade places. It is the size of the button while it is
    // shut and the size of the panel once it is open, and both of those hang inside it, pinned to
    // the corner it is anchored at. Everything past the current size is clipped, so opening reads
    // as the button stretching out into the window and the content coming into view behind it.
    import AgentFab from "./AgentFab.svelte";
    import AgentPanel, {PANEL_SIZE} from "./AgentPanel.svelte";
    import {agentRepository, type AgentProcessStatus} from "$lib/repository/AgentRepository";

    let status = $state<AgentProcessStatus | null>(null);
    let isOpen = $state(false);

    /**
     * What the button measures, which is what the card is wide while it is shut. Measured rather
     * than stated: the button is a circle when the agent idles and a pill when it works, and
     * `width: max-content` is a keyword the browser cannot animate away from -- an explicit pixel
     * width is what makes the morph a transition. It stands in until the first measurement.
     */
    let fabWidth = $state(0);

    // In an effect rather than at module scope: this only runs in the browser, and the socket is
    // hung up when the card goes. One connection feeds both the button and the panel.
    $effect(() => {
        const connection = agentRepository.watchProcess({onStatus: (pushed) => (status = pushed)});

        return () => connection.close();
    });

    // A click next to the card leaves it alone -- it is a window, not a popover, and reaching for
    // the mail underneath must not cost what is on it. It closes by its own button, or by Escape.
    function closeOnEscape(event: KeyboardEvent) {
        if (event.key === 'Escape') isOpen = false;
    }
</script>

<svelte:window onkeydown={closeOnEscape} />

<!-- Anchored at its bottom right corner, which is the corner it grows away from. Above the mail
     table's sticky header, below the sidebar sheet. Open on a phone the panel takes the whole
     screen, so the card gives up its inset and its rounding there; from a tablet width on it stays
     a window with a gap around it. -->
<div
        class="fixed z-30 overflow-hidden bg-background drop-shadow-xl
               transition-[width,height,border-radius,bottom,right] duration-300 ease-out
               {isOpen
                   ? `${PANEL_SIZE} bottom-0 right-0 rounded-none md:bottom-6 md:right-6 md:rounded-3xl`
                   : 'h-12 w-max bottom-6 right-6 rounded-4xl'}"
        style={isOpen || fabWidth === 0 ? '' : `width: ${fabWidth}px`}
>
    <!-- Both of these keep their size and their place through the whole move; what changes is how
         much of them the card lets through. -->
    <div
            class="absolute bottom-0 right-0 w-max transition-opacity
                   {isOpen ? 'opacity-100 delay-200 duration-150' : 'pointer-events-none opacity-0 duration-100'}"
    >
        <AgentPanel {status} onClose={() => (isOpen = false)} />
    </div>

    <div
            bind:clientWidth={fabWidth}
            class="absolute bottom-0 right-0 w-max transition-opacity duration-150
                   {isOpen ? 'pointer-events-none opacity-0' : 'opacity-100'}"
    >
        <!-- Only opens: once it is open the button is behind the panel, and the way back out is
             the panel's own close button. -->
        <AgentFab {status} expanded={isOpen} onclick={() => (isOpen = true)} />
    </div>
</div>
