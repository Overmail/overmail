<script lang="ts">
    import {cn} from "$lib/utils.js";

    let {
        key,
        isPressed = false,
        class: className,
    }: {
        key: string;
        isPressed?: boolean;
        class?: string;
    } = $props();
</script>

<!-- Keycap look with a single flat bevel: the thicker bottom border reads as the key's side,
     no gradients or shadows needed. `class` goes last, so a caller's utility wins over ours. -->
<kbd
        class={cn(
            "flex h-12 w-12 items-center justify-center rounded-md border border-b-4 bg-background font-sans text-xl text-foreground",
            "transition-[translate,border-width,background-color] duration-75 ease-out",
            // Pressed: the bevel collapses and the cap moves down by exactly what it lost, so the
            // bottom edge stays put and only the top of the key sinks.
            isPressed && "translate-y-[3px] border-b bg-muted",
            className,
        )}
>{key}</kbd>
