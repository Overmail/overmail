<script lang="ts">
    import type {Snippet} from "svelte";

    let {children}: {children: Snippet} = $props();

    let content: HTMLDivElement | null = $state(null);
    /** Null until the first measurement, so the first paint is not animated from zero. */
    let height: number | null = $state(null);

    // A height in pixels is the only thing `transition` can interpolate -- `auto` does not
    // animate -- so the content is measured and the box outside it is told what to be. A
    // ResizeObserver rather than a one-off read: a step grows as its own content arrives,
    // like the folder table filling up.
    $effect(() => {
        if (!content) return;
        const observer = new ResizeObserver(([entry]) => {
            height = entry.borderBoxSize?.[0]?.blockSize ?? entry.contentRect.height;
        });
        observer.observe(content);
        return () => observer.disconnect();
    });
</script>

<div
        class="overflow-hidden transition-[height] duration-200 ease-out motion-reduce:transition-none"
        style={height === null ? "" : `height: ${height}px`}
>
    <div bind:this={content}>
        {@render children()}
    </div>
</div>
