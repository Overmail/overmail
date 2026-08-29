<script lang="ts">
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import {cn} from "$lib/utils.js";
    import type {StackEmail} from "$lib/app/my-stack/EmailStackViewModel.svelte";

    let {
        emails,
        class: className,
    }: {
        /** Newest first: `emails[0]` is the card on top. */
        emails: StackEmail[];
        class?: string;
    } = $props();

    /** How far down the stack we still offset a card; deeper ones share the last position. */
    const MAX_DEPTH = 4;

    // Math.random() would hand SSR and the client different numbers and blow up hydration, so the
    // jitter is a hash of the mail id instead: random-looking, but the same on both sides and
    // stable while the stack is being worked through.
    function hash(id: string, salt: number): number {
        let h = 2166136261 ^ salt;
        for (let i = 0; i < id.length; i++) {
            h = Math.imul(h ^ id.charCodeAt(i), 16777619);
        }
        return (h >>> 0) / 4294967295;
    }

    /** A hashed value in [-spread, spread]. */
    function jitter(id: string, salt: number, spread: number): number {
        return (hash(id, salt) * 2 - 1) * spread;
    }

    const stack = $derived(
        emails.map((email, index) => {
            const depth = Math.min(index, MAX_DEPTH);
            const id = email.id;

            return {
                email: email,
                id: id,
                // The top card stays straight and centred; everything below drifts.
                rotation: index === 0 ? 0 : jitter(id, 1, 2.5),
                offsetX: index === 0 ? 0 : jitter(id, 2, 10),
                offsetY: index === 0 ? 0 : depth * 10 + jitter(id, 3, 6),
                // Faded via an opaque-card + translucent-overlay sandwich rather than opacity on
                // the card itself: the cards below have to stay solid, or the whole stack shows
                // through itself.
                fade: index === 0 ? 0 : Math.min(0.2 + depth * 0.15, 0.65),
                // Later cards paint further back. Room for 100 mails before it collides with
                // anything else on the page.
                z: emails.length - index,
            };
        }),
    );
</script>

<!-- Every card is positioned against this box, so a long mail never grows the stack. Nothing
     clips the cards themselves: the drop shadow has to stay visible, or the ones behind stop
     reading as separate cards. -->
<div class={cn("relative isolate w-3xl", className)}>
    {#each stack as { email, id, rotation, offsetX, offsetY, fade, z } (id)}
        {#if fade === 0}
            <!-- The scroll box goes around the card, not inside it: the card keeps its natural
                 height and the box slides it, so a long mail moves as one object — background,
                 header and shadow together — instead of the body sliding under a header that
                 stays put.

                 inset-y-0 is what makes that work at all: only a box with a height of its own can
                 overflow. w-fit + left-1/2 keeps it hugging the card, px-8 is there because a
                 scroll box clips and the drop shadow would go with it, and pb-32 matches the
                 shortcut bar: the box reaches the bottom of the page so the card disappears under
                 the bar, and that padding is extra scroll range at the end, which brings the last
                 lines back out from under it. -->
            <div
                    class="card-scroll absolute inset-y-0 left-1/2 w-fit -translate-x-1/2 overflow-y-auto overscroll-contain px-8 pt-2 pb-32"
                    style="z-index: {z}"
            >
                <EmailCard {...email} />
            </div>
        {:else}
            <!-- top-0 only: the wrapper hugs the card, so the tint overlay below lines up with the
                 card instead of the whole column, and the card keeps its natural height. A long
                 mail then runs past the bottom of the stack, where the page's overflow-hidden cuts
                 it off behind the shortcut bar rather than mid-card. -->
            <div
                    class="pointer-events-none absolute top-0 left-1/2 blur-[1px]"
                    style="z-index: {z}; transform: translate(calc(-50% + {offsetX}px), {offsetY}px) rotate({rotation}deg)"
                    aria-hidden="true"
            >
                <!-- overflow-hidden instead of the card's own scrolling: the cards behind are
                     decoration and must not eat the wheel. -->
                <EmailCard {...email} class="overflow-hidden" />
                <div
                        class="pointer-events-none absolute inset-0 rounded-2xl bg-background"
                        style="opacity: {fade}"
                ></div>
            </div>
        {/if}
    {/each}
</div>

<style>
    /* No scrollbar: the card is supposed to read as a sheet of paper being pushed along, and a
       track pinned to the edge gives away that it is a scroll box. */
    .card-scroll {
        scrollbar-width: none;
    }

    .card-scroll::-webkit-scrollbar {
        display: none;
    }
</style>
