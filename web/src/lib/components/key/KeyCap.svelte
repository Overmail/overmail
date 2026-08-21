<script lang="ts">
    import {cn} from "$lib/utils.js";

    let {
        key,
        isPressed = false,
        label,
        onclick,
        class: className,
    }: {
        key: string;
        isPressed?: boolean;
        /** Accessible name: on its own a cap only says "A". */
        label?: string;
        onclick?: () => void;
        class?: string;
    } = $props();
</script>

<!-- Keycap look with a single flat bevel: the thicker bottom border reads as the key's side, no
     gradients or shadows needed. A button, so the shortcut can also be clicked. `data-pressed`
     rather than a class, so callers can colour hover and pressed with the same variants, and
     `class` goes last so their utility wins over ours. -->
<button
        type="button"
        aria-label={label}
        data-pressed={isPressed ? "" : undefined}
        {onclick}
        class={cn(
            "flex h-12 w-12 items-center justify-center rounded-md border border-b-4 bg-background font-mono text-lg text-foreground",
            "transition-[translate,border-width,background-color,border-color] duration-75 ease-out",
            // Pressed: the bevel collapses and the cap moves down by exactly what it lost, so the
            // bottom edge stays put and only the top of the key sinks.
            isPressed && "translate-y-0.75 border-b bg-muted",
            className,
        )}
><kbd class="font-sans">{key}</kbd></button>
