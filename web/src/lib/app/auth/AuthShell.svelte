<script lang="ts">
    import {onMount, type Snippet} from "svelte";
    import {claimStepIntro, stepIn, stepOut} from "./stepTransition";

    let {
        title,
        description,
        onsubmit,
        children,
        actions,
    }: {
        title?: string,
        description?: string,
        /** Makes the content a form; called on submit, the page does not reload. */
        onsubmit?: () => void,
        /** The fields, the submit button and the error under them. */
        children?: Snippet,
        /** The links below the form: another way to sign in, starting over. */
        actions?: Snippet,
    } = $props();

    const animate = claimStepIntro();

    let element: HTMLDivElement;

    // `autofocus` alone does not do it here: Svelte only focuses an element it inserts when nothing
    // else has the focus, and the step on its way out may still hold it. preventScroll, or the
    // browser scrolls the field into view in the middle of the transition.
    onMount(() => {
        element.querySelector<HTMLElement>("[autofocus]")?.focus({preventScroll: true});
    });
</script>

<!-- Every step sits in the same cell of the page's grid, so the one leaving and the one arriving
     overlap for the length of the transition instead of stacking. Each one is a whole page and
     scrolls on its own when it does not fit. -->
<div bind:this={element}
     class="col-start-1 row-start-1 h-full min-h-0 overflow-y-auto p-6 pt-10 lg:p-16 lg:pt-32 will-change-transform"
     in:stepIn={{animate}} out:stepOut>
    <div class="flex w-full max-w-md flex-col gap-8">
        {#if title || description}
            <div class="flex flex-col gap-3">
                {#if title}
                    <h1 class="font-display text-3xl leading-tight text-balance sm:text-4xl lg:text-5xl">{title}</h1>
                {/if}
                {#if description}
                    <p class="text-muted-foreground">{description}</p>
                {/if}
            </div>
        {/if}

        {#if onsubmit}
            <form class="flex flex-col gap-2" onsubmit={(e) => { e.preventDefault(); onsubmit(); }}>
                {@render children?.()}
            </form>
        {:else}
            {@render children?.()}
        {/if}

        {@render actions?.()}
    </div>
</div>
