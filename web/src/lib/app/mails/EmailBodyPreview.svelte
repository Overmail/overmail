<!--
    A mail body as a preview: rendered at the width mails are designed for, scaled down to the box
    it is given, with a magnifying lens under the cursor so it can be read without opening the mail.

    The box comes from the caller -- this fills it and clips what does not fit, so a preview is a
    preview and never grows the layout around it.
-->
<script lang="ts">
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";
    import {cn} from "$lib/utils";
    import {_} from "svelte-i18n";

    let {
        body,
        active = true,
        magnifier = true,
        lensRadius = 120,
        magnification = 2,
        class: className,
    }: {
        body: {text: string | null; html: string | null};
        /** False for a preview a caller keeps mounted but hidden: no lens on one nobody sees. */
        active?: boolean;
        /** Off where a screen full of previews would carry a second iframe each for nothing. */
        magnifier?: boolean;
        lensRadius?: number;
        /** Magnification of the lens, relative to the preview as displayed. */
        magnification?: number;
        class?: string;
    } = $props();

    /** The width html mails are typically designed for; that is what they are rendered at. */
    const DESIGN_WIDTH = 640;

    let width = $state(0);
    // Unlike `transform`, `zoom` scales the layout along with it -- no spacer and no measuring
    // needed. Dynamic, so the mail fills the full width it is given.
    const zoom = $derived(width > 0 ? width / DESIGN_WIDTH : 0.45);

    const isEmpty = $derived(!body.html && !body.text);

    /**
     * Cursor position of the lens, null when none is active. `panel*` places the circle in the
     * box, `content*` points at the same spot in the (possibly scrolled) content.
     */
    let lens: {panelX: number; panelY: number; contentX: number; contentY: number} | null = $state(null);

    // A hidden preview keeps no lens: it would still be sitting there when the caller shows the
    // preview again, under a cursor that has long moved on.
    $effect(() => {
        if (!active) lens = null;
    });

    function onMousemove(event: MouseEvent) {
        const layer = event.currentTarget as HTMLElement;
        const rect = layer.getBoundingClientRect();
        const panelX = event.clientX - rect.left;
        const panelY = event.clientY - rect.top;
        lens = {
            panelX,
            panelY,
            contentX: panelX + layer.scrollLeft,
            contentY: panelY + layer.scrollTop,
        };
    }
</script>

<div class={cn("relative overflow-hidden", className)} bind:clientWidth={width}>
    {#if isEmpty}
        <div class="flex h-full items-center justify-center text-xs text-muted-foreground">
            {$_('mails.preview.noContent')}
        </div>
    {:else}
        <div
                class={cn(
                    "absolute inset-0 overflow-y-auto overflow-x-hidden",
                    body.text && !body.html && "p-2",
                    magnifier && "cursor-none",
                )}
                onmousemove={magnifier ? onMousemove : undefined}
                onmouseleave={() => lens = null}
                role="presentation"
        >
            <!-- pointer-events-none: the preview really is only a preview. Without it the iframe
                 swallows the mousemove events and the lens freezes. -->
            <div class="pointer-events-none">
                {#if body.html}
                    <!-- Fixed design width, zoom scaled to the box: full width, never a horizontal
                         scrollbar. -->
                    <div style:zoom={zoom} style:width="{DESIGN_WIDTH}px">
                        <EmailHtmlBody html={body.html}/>
                    </div>
                {:else}
                    <pre class="whitespace-pre-wrap font-sans text-xs">{body.text}</pre>
                {/if}
            </div>
        </div>

        <!-- The lens is a sibling of the scroll layer rather than a child: inside it, it would
             grow the scroll area at the edges. It is placed in box coordinates and its content is
             offset by the layer's scroll position. Mounted whether or not it is shown, so
             hovering never makes the iframe parse the mail and refetch its images. -->
        {#if magnifier}
            <div
                    class={cn(
                        "pointer-events-none absolute z-10 overflow-hidden rounded-full border-2 bg-background shadow-lg",
                        lens === null && "invisible",
                    )}
                    style:width="{lensRadius * 2}px"
                    style:height="{lensRadius * 2}px"
                    style:left="{(lens?.panelX ?? 0) - lensRadius}px"
                    style:top="{(lens?.panelY ?? 0) - lensRadius}px"
            >
                <div
                        class="absolute"
                        style:left="{lensRadius - (lens?.contentX ?? 0) * magnification}px"
                        style:top="{lensRadius - (lens?.contentY ?? 0) * magnification}px"
                >
                    {#if body.html}
                        <div style:zoom={zoom * magnification} style:width="{DESIGN_WIDTH}px">
                            <EmailHtmlBody html={body.html}/>
                        </div>
                    {:else}
                        <pre
                                class="whitespace-pre-wrap font-sans text-xs"
                                style:zoom={magnification}
                                style:width="{width}px"
                        >{body.text}</pre>
                    {/if}
                </div>
            </div>
        {/if}
    {/if}
</div>
