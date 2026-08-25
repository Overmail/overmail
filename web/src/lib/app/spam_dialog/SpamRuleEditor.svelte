<script lang="ts">
    import { onMount } from 'svelte';
    import { cn } from '$lib/utils.js';
    import type { RuleReadout, SpamRule } from './rule';
    import type { SpamEditor } from './editor';

    let {
        initial = null,
        onchange,
        class: className
    }: {
        /** The rule the editor opens with. Null starts with the bare root block. */
        initial?: SpamRule | null;
        /** Called once with the state the editor opens in, and after every change to the blocks. */
        onchange: (readout: RuleReadout) => void;
        class?: string;
    } = $props();

    let host = $state<HTMLDivElement | null>(null);
    let anchor = $state<HTMLDivElement | null>(null);

    onMount(() => {
        const hostElement = host;
        const anchorElement = anchor;
        if (!hostElement || !anchorElement) return;

        let editor: SpamEditor | null = null;
        let dropped = false;

        calibrate(anchorElement);

        // Blockly is off limits to the server -- it reaches for `document` while it is being
        // imported -- and this component is part of a page that renders there, so it comes in from
        // here rather than from the top of the module.
        import('./editor').then((module) => {
            if (dropped) return;

            editor = module.createSpamEditor({
                host: hostElement,
                widgetHost: anchorElement,
                initial,
                onchange
            });
        });

        // A window resize is the only size change Blockly watches by itself, and the dialog can
        // change size without one -- so can this box, while the dialog is still zooming in.
        const observer = new ResizeObserver(() => {
            calibrate(anchorElement);
            editor?.resize();
        });
        observer.observe(hostElement);

        // The last word before Blockly opens anything: by the time a click reaches a block, the
        // dialog has stopped moving, whatever it was doing when the workspace was injected.
        const recalibrate = () => calibrate(anchorElement);
        hostElement.addEventListener('pointerdown', recalibrate, true);

        return () => {
            dropped = true;
            observer.disconnect();
            hostElement.removeEventListener('pointerdown', recalibrate, true);
            editor?.dispose();
        };
    });

    /**
     * Pins the box Blockly's dropdowns, field editors and tooltips hang in to the page's origin,
     * which is the coordinate system Blockly positions them in.
     *
     * Measured rather than assumed: an absolutely positioned element measures from its containing
     * block, and which element that is depends on what carries a transform above it — the dialog
     * does while it zooms in. So this reads where the box actually landed and takes the difference
     * out of its own offset.
     */
    function calibrate(element: HTMLElement) {
        const box = element.getBoundingClientRect();
        const left = Number.parseFloat(element.style.left || '0');
        const top = Number.parseFloat(element.style.top || '0');

        element.style.left = `${left - box.x - window.scrollX}px`;
        element.style.top = `${top - box.y - window.scrollY}px`;
    }
</script>

<div class={cn('overflow-hidden rounded-2xl border', className)}>
    <!-- Sized from the outside: Blockly measures this element and fills it, so its own size has to
         come from the wrapper rather than from what is in it. -->
    <div bind:this={host} class="size-full"></div>
</div>

<!-- Empty, and no size of its own. Blockly's dropdowns, field editors and tooltips hang in here
     instead of under the body, because the dialog switches pointer events off for everything
     outside itself and pulls stray focus back in. -->
<div bind:this={anchor} class="fixed size-0"></div>
