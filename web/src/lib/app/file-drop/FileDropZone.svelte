<script lang="ts">
    import {fade, fly} from "svelte/transition";
    import {cubicOut} from "svelte/easing";
    import {_} from "svelte-i18n";
    import EnvelopeSimpleIcon from "phosphor-svelte/lib/EnvelopeSimpleIcon";
    import FileArrowUpIcon from "phosphor-svelte/lib/FileArrowUpIcon";
    import {type DragPreview, draggesFiles, previewDrag} from "./fileDrop";
    import {useFileDrop} from "./fileDropContext.svelte";
    import {inboxLabel} from "$lib/repository/InboxRepository";

    /**
     * Makes its parent take dropped files: tints it while files are dragged over it and shows a
     * bubble at the bottom of the window that says where they will go.
     *
     * Place it as a child of a `relative` element; it covers that element and listens on it.
     * Nothing under it has to know about files -- the overlay itself takes no pointer events.
     */
    const drop = useFileDrop();

    let anchor: HTMLElement | undefined = $state();
    let preview = $state<DragPreview | null>(null);

    // dragenter and dragleave fire for every element the pointer crosses inside the zone, a leave
    // for the one it left after the enter of the one it entered. Counting says it left the zone.
    let depth = 0;

    const accepts = (event: DragEvent) =>
        drop !== null && !drop.disabled && draggesFiles(event.dataTransfer);

    function reset() {
        depth = 0;
        preview = null;
    }

    $effect(() => {
        const zone = anchor?.parentElement;
        if (!zone || !drop) return;

        function onEnter(event: DragEvent) {
            if (!accepts(event)) return;
            event.preventDefault();
            if (depth++ === 0) {
                preview = previewDrag(event.dataTransfer!);
                if (preview.type !== "unsupported") drop!.refreshInboxes();
            }
        }

        function onOver(event: DragEvent) {
            if (!accepts(event)) return;
            event.preventDefault();
            // The window refuses files; this zone is where they are taken.
            event.stopPropagation();
            event.dataTransfer!.dropEffect = preview?.type === "unsupported" ? "none" : "copy";
        }

        function onLeave(event: DragEvent) {
            if (!accepts(event)) return;
            depth = Math.max(0, depth - 1);
            if (depth === 0) preview = null;
        }

        function onDrop(event: DragEvent) {
            if (!accepts(event)) return;
            event.preventDefault();
            event.stopPropagation();
            const files = Array.from(event.dataTransfer!.files);
            const took = preview?.type !== "unsupported";
            reset();
            if (took && files.length > 0) drop!.handle(files);
        }

        zone.addEventListener("dragenter", onEnter);
        zone.addEventListener("dragover", onOver);
        zone.addEventListener("dragleave", onLeave);
        zone.addEventListener("drop", onDrop);
        window.addEventListener("dragend", reset);
        return () => {
            zone.removeEventListener("dragenter", onEnter);
            zone.removeEventListener("dragover", onOver);
            zone.removeEventListener("dragleave", onLeave);
            zone.removeEventListener("drop", onDrop);
            window.removeEventListener("dragend", reset);
        };
    });

    const isEml = $derived(preview?.type === "kind" && preview.kind === "eml");

    /** Where the files will go: the one inbox by name, otherwise the choice that follows. */
    const target = $derived.by(() => {
        const inboxes = drop?.inboxes;
        if (inboxes?.length === 1) return inboxLabel(inboxes[0]);
        if (inboxes && inboxes.length > 1) return $_("app.fileDrop.target.choose");
        return $_("app.fileDrop.target.inbox");
    });
</script>

<span bind:this={anchor} class="hidden"></span>

{#if preview && preview.type !== "unsupported"}
    <!-- overflow-clip rather than hidden: it keeps the glow inside the list without making this a
         scroll container, which would pin the sticky bubble to it instead of to the window. -->
    <div
            transition:fade={{duration: 150}}
            class="pointer-events-none absolute inset-0 z-30 flex flex-col justify-end overflow-clip rounded-3xl bg-primary/4 ring-2 ring-primary/70 ring-inset"
            aria-live="polite"
    >
        <!-- Sticky, so a list longer than the window still shows the bubble where the eye is:
             at the bottom of what is on screen, or of the list where that ends first. The glow
             travels with it for the same reason. -->
        <div class="sticky bottom-0 flex justify-center px-4 pt-10 pb-6">
            <div class="absolute bottom-0 left-1/2 h-72 w-[44rem] max-w-[200%] -translate-x-1/2 translate-y-1/3 bg-radial from-primary/30 to-transparent to-65%"></div>

            <div
                    in:fly={{y: 20, duration: 260, easing: cubicOut}}
                    class="relative flex max-w-full flex-col items-center gap-4"
            >
                <div class="flex size-14 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-[0_0_0_8px_color-mix(in_oklab,var(--primary)_18%,transparent),0_0_0_16px_color-mix(in_oklab,var(--primary)_8%,transparent)]">
                    {#if isEml}
                        <EnvelopeSimpleIcon class="size-6" weight="bold" />
                    {:else}
                        <FileArrowUpIcon class="size-6" weight="bold" />
                    {/if}
                </div>
                <p class="max-w-full truncate rounded-full bg-foreground px-4 py-1.5 text-sm text-background shadow-lg">
                    {$_(isEml ? "app.fileDrop.eml" : "app.fileDrop.unknown", {values: {count: preview.count}})}
                    <span class="font-semibold">{target}</span>
                </p>
            </div>
        </div>
    </div>
{/if}
