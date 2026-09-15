<!--
    A name being edited in place: the box that stands where the name was.

    One component for both places a view is named -- the row in the sidebar and the heading over
    the listing -- because the rules are the same everywhere and none of them are obvious: the
    name is there to be replaced rather than clicked into, Enter and a click elsewhere keep what
    was typed, Escape and an empty box keep the old name, and a blur that nobody caused is not the
    end of anything.
-->
<script lang="ts">
    import {renamedTo} from "$lib/app/views/rename";

    const {
        name,
        onEnd,
        label,
        class: className,
    }: {
        /** What it is called now: the value the box opens with, and what a change is measured against. */
        name: string;
        /**
         * The end of the editing: the new name, or null when the name stays as it is -- an empty
         * box, Escape, or the same name typed again, see [renamedTo].
         */
        onEnd: (name: string | null) => void;
        label: string;
        class?: string;
    } = $props();

    /** The input while there is one, for the click outside that ends the editing. */
    let input: HTMLInputElement | null = null;

    /** Set once the editing is over, so the blur that follows does not submit a second time. */
    let settled = false;

    /** The input as it appears: the name is there to be replaced, not to be clicked into. */
    function edit(node: HTMLInputElement) {
        settled = false;
        input = node;

        // On the next frame rather than straight away: an attachment runs while the fragment it
        // belongs to is still being put into the document, and focus() on an element that is not
        // in it yet does nothing -- the caret ends up nowhere and the first key press is lost.
        const frame = requestAnimationFrame(() => {
            node.focus();
            node.select();
        });

        return () => {
            cancelAnimationFrame(frame);
            if (input === node) input = null;
        };
    }

    /**
     * A click anywhere else keeps what was typed, like Enter does.
     *
     * The click, not the input's own blur: the focus leaves the input without anybody having
     * clicked -- SvelteKit puts it back on the body when a navigation ends, and the second of the
     * two clicks that opened the editor started one. That blur used to end the editing the moment
     * it began; here it is not a reason to, and the input takes the focus back instead.
     */
    $effect(() => {
        const commit = (event: PointerEvent) => {
            const node = input;
            if (node === null) return;
            if (event.target instanceof Node && node.contains(event.target)) return;

            finish(renamedTo(name, node.value));
        };

        document.addEventListener("pointerdown", commit, true);

        return () => document.removeEventListener("pointerdown", commit, true);
    });

    function onBlur(event: FocusEvent & {currentTarget: HTMLInputElement}) {
        // Ended already -- by Enter, Escape or the click above, which runs before this.
        if (settled) return;

        // Nowhere in particular, which is the navigation's doing rather than the user's.
        if (event.relatedTarget === null || event.relatedTarget === document.body) {
            event.currentTarget.focus();
            return;
        }

        // Something else took the focus, Tab for instance: keep what was typed.
        finish(renamedTo(name, event.currentTarget.value));
    }

    function finish(next: string | null) {
        if (settled) return;

        settled = true;
        onEnd(next);
    }

    function onKeydown(event: KeyboardEvent & {currentTarget: HTMLInputElement}) {
        if (event.key === "Enter") {
            event.preventDefault();
            // Kept to the input: what was typed here is nobody else's key press.
            event.stopPropagation();
            finish(renamedTo(name, event.currentTarget.value));
        } else if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();
            finish(null);
        }
    }
</script>

<input
        {@attach edit}
        class={className}
        value={name}
        aria-label={label}
        onkeydown={onKeydown}
        onblur={onBlur}
/>
