<script lang="ts">
    import {onMount, type Snippet} from "svelte";
    import {cubicOut} from "svelte/easing";
    import {cn} from "$lib/utils.js";
    import * as Kbd from "$lib/components/ui/kbd";
    import {_} from "svelte-i18n";

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

    /**
     * Ein- und Ausblenden wie ein Popover: Opacity plus ein leichtes Aufsteigen aus der
     * Ankerkante. Animiert werden die eigenständigen Properties `translate` und `scale` —
     * `transform` gehört clampToViewport und würde sich sonst mit der Transition überschreiben.
     */
    function float(_node: Element, {duration}: {duration: number}) {
        return {
            duration,
            easing: cubicOut,
            css: (t: number, u: number) => `opacity: ${t}; scale: ${0.96 + 0.04 * t}; translate: 0 ${u * 8}px;`,
        };
    }

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
        in:float={{duration: 150}}
        out:float={{duration: 100}}
        class={cn(
            "absolute z-50 max-w-[calc(100vw-2rem)] max-h-64 origin-bottom-left overflow-y-auto rounded-md border bg-popover text-sm text-popover-foreground shadow-md",
            className,
        )}
        style:left="{left}px"
        style:bottom="{bottom}px"
>
    {@render children()}

    <div class="pointer-events-none absolute inset-x-0 bottom-0 h-12 bg-linear-to-b from-transparent to-popover">
        <div class="absolute inset-0 backdrop-blur-[2px] mask-[linear-gradient(to_bottom,transparent_0%,black_25%)]"></div>
        <div class="absolute inset-0 backdrop-blur-xs mask-[linear-gradient(to_bottom,transparent_25%,black_50%)]"></div>
        <div class="absolute inset-0 backdrop-blur-sm mask-[linear-gradient(to_bottom,transparent_50%,black_75%)]"></div>
        <div class="absolute inset-0 backdrop-blur-lg mask-[linear-gradient(to_bottom,transparent_75%,black_100%)]"></div>

        <div class="relative flex h-full flex-row items-end gap-3 px-2 pb-1.5 text-xs text-muted-foreground">
            <div class="flex flex-row items-center gap-1">
                <span>{$_('ai.shortcuts.navigate')}</span>
                <Kbd.Group>
                    <Kbd.Root>&uparrow;</Kbd.Root>
                    <Kbd.Root>&downarrow;</Kbd.Root>
                </Kbd.Group>
            </div>

            <div class="flex flex-row items-center gap-1">
                <span>{$_('ai.shortcuts.select')}</span>
                <Kbd.Group>
                    <Kbd.Root>&crarr;</Kbd.Root>
                </Kbd.Group>
            </div>

            <div class="flex flex-row items-center gap-1">
                <span>{$_('ai.shortcuts.dismiss')}</span>
                <Kbd.Group>
                    <Kbd.Root>Esc</Kbd.Root>
                </Kbd.Group>
            </div>
        </div>
    </div>
</div>
