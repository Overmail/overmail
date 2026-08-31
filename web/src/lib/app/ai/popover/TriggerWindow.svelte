<script lang="ts">
    import {onMount, type Snippet} from "svelte";
    import {cn} from "$lib/utils.js";

    let {
        left,
        bottom,
        class: className,
        children,
    }: {
        left: number;
        bottom: number;
        class?: string;
        children: Snippet;
    } = $props();

    let element: HTMLDivElement;

    // Mindestabstand zur Viewport-Kante (p-4).
    const MARGIN = 16;

    // Hält das Fenster im Viewport, damit es weder abgeschnitten wird noch die Seite
    // zum Overflowen bringt. Horizontal per translate; vertikal bleibt die Unterkante
    // am Anker (über dem Prompt) und stattdessen wird die Höhe begrenzt — das Fenster
    // rutscht also nie nach unten über Cursor oder Eingabefeld.
    function clampToViewport() {
        if (!element) return;

        element.style.transform = "";
        element.style.maxHeight = "";
        const rect = element.getBoundingClientRect();

        let dx = 0;
        if (rect.right > window.innerWidth - MARGIN) dx = window.innerWidth - MARGIN - rect.right;
        if (rect.left + dx < MARGIN) dx = MARGIN - rect.left;
        element.style.transform = dx !== 0 ? `translateX(${dx}px)` : "";

        const available = rect.bottom - MARGIN;
        if (rect.height > available) {
            element.style.maxHeight = `${Math.max(0, available)}px`;
        }
    }

    $effect(() => {
        void left;
        void bottom;
        clampToViewport();
    });

    onMount(() => {
        // Größenänderungen des Inhalts (z.B. nachgeladene Suchergebnisse) neu klammern.
        const observer = new ResizeObserver(clampToViewport);
        observer.observe(element);
        window.addEventListener("resize", clampToViewport);

        return () => {
            observer.disconnect();
            window.removeEventListener("resize", clampToViewport);
        };
    });
</script>

<div
        bind:this={element}
        class={cn(
            "absolute z-50 max-w-[calc(100vw-2rem)] max-h-64 overflow-y-auto rounded-md border bg-popover text-sm text-popover-foreground shadow-md",
            className,
        )}
        style:left="{left}px"
        style:bottom="{bottom}px"
>
    {@render children()}
</div>
