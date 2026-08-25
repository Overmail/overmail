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

    onMount(() => {
        const hostElement = host;
        if (!hostElement) return;

        let editor: SpamEditor | null = null;
        let dropped = false;

        // Blockly is off limits to the server -- it reaches for `document` while it is being
        // imported -- and this component is part of a page that renders there, so it comes in from
        // here rather than from the top of the module.
        import('./editor').then((module) => {
            if (dropped) return;

            editor = module.createSpamEditor({ host: hostElement, initial, onchange });
        });

        // A window resize is the only size change Blockly watches by itself, and the dialog can
        // change size without one -- so can this box, while the dialog is still zooming in.
        const observer = new ResizeObserver(() => editor?.resize());
        observer.observe(hostElement);

        // Escape closes the dialog, and a block hanging off the pointer is not what the user is
        // giving up on when they press it. Capture phase, and stopped dead: the dialog listens for
        // Escape on the document itself, and Blockly hides its own popovers on it.
        const abortDrag = (event: KeyboardEvent) => {
            if (event.key !== 'Escape' || !editor?.abortDrag()) return;

            event.preventDefault();
            event.stopPropagation();
            event.stopImmediatePropagation();
        };
        document.addEventListener('keydown', abortDrag, true);

        return () => {
            dropped = true;
            observer.disconnect();
            document.removeEventListener('keydown', abortDrag, true);
            editor?.dispose();
        };
    });
</script>

<div class={cn('overflow-hidden rounded-2xl border', className)}>
    <!-- Sized from the outside: Blockly measures this element and fills it, so its own size has to
         come from the wrapper rather than from what is in it. -->
    <div bind:this={host} class="size-full"></div>
</div>

<style>
    /* Blockly's stylesheet and its renderer's are both prepended to <head> so that app CSS can
       override them -- see Css.inject. Prepended, not weaker: these selectors have to match the
       renderer's own for the cascade to come down on this side, which is why they carry an
       ancestor they do not otherwise need. */

    /* The group labels in the palette, as the section labels they are. */
    :global(.blocklyFlyout .blocklyFlyoutLabel .blocklyFlyoutLabelText) {
        fill: var(--muted-foreground);
        font-size: 8pt;
        letter-spacing: 0.06em;
        text-transform: uppercase;
    }

    /* The palette already sits on its own surface; the label needs no plate of its own. */
    :global(.blocklyFlyout .blocklyFlyoutLabel .blocklyFlyoutLabelBackground) {
        fill: transparent;
    }

    /* The menu a dropdown field opens. Blockly paints it in the block's colour from an inline
       style, so only its shape is set here. */
    :global(.blocklyDropDownDiv) {
        border-radius: var(--radius-lg);
        border-width: 1px;
        box-shadow: 0 12px 32px -12px rgb(0 0 0 / 0.45);
        padding: 0.25rem;
    }

    :global(.blocklyDropDownDiv .blocklyDropdownMenu .blocklyMenuItem) {
        border-radius: var(--radius-sm);
        font-size: 0.8125rem;
        padding: 0.375rem 1.25rem 0.375rem 0.625rem;
    }

    :global(.blocklyDropDownDiv .blocklyDropdownMenu .blocklyMenuItemHighlight) {
        background-color: rgb(255 255 255 / 0.16);
    }

    /* The text field on a comparison block. */
    :global(.blocklyWidgetDiv .blocklyHtmlInput) {
        border-radius: var(--radius-sm);
    }
</style>
