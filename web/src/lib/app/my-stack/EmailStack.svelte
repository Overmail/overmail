<script module lang="ts">
    import type {EmailCardParticipant} from "$lib/app/my-stack/EmailCard.svelte";
    import type {EmailClassification} from "$lib/app/my-stack/classification";

    /** One mail in the stack; `id` keys the `#each`, so it has to be stable across loads. */
    export type EmailStackEntry = {
        id: string;
        sender: EmailCardParticipant & { avatarUrl?: string };
        sent: string;
        to: EmailCardParticipant[];
        cc?: EmailCardParticipant[];
        bcc?: EmailCardParticipant[];
        subject: string;
        body: string;
        tags?: string[];
        /** Absent as long as the mail is still waiting for a decision. */
        classification?: EmailClassification;
    };
</script>

<script lang="ts">
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import {classificationTint} from "$lib/app/my-stack/classification";
    import {cn} from "$lib/utils.js";

    let {
        emails,
        currentId,
        class: className,
    }: {
        emails: EmailStackEntry[];
        /** The mail on top of the stack. No match means every mail sits in the back. */
        currentId?: string;
        class?: string;
    } = $props();

    /** How far down the stack we still offset a card; deeper ones would share the last position. */
    const MAX_DEPTH = 4;

    /**
     * How many decided mails are kept mounted behind the current one. They are off screen and only
     * there so that taking a decision back can slide the card in again; undoing further back than
     * this shows the mail without that slide, which beats holding every mail ever decided on.
     */
    const DONE_WINDOW = 3;

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

    const currentPosition = $derived.by(() => {
        const index = emails.findIndex((mail) => mail.id === currentId);
        return index < 0 ? emails.length : index;
    });

    const stack = $derived.by(() => {
        let depth = 0;

        return emails.flatMap((email, index) => {
            const {id, classification, ...card} = email;

            // A decision beats being the current mail: a mail classified while it is on top has to
            // leave, and the caller is free to move `currentId` on in the same update.
            const done = classification !== undefined;
            const current = !done && id === currentId;
            if (!done && !current) depth += 1;

            // Past the last offset position a card is covered by the ones in front of it, so it is
            // left out of the DOM entirely rather than painted and animated for nothing.
            if (depth > MAX_DEPTH) return [];
            if (done && index < currentPosition - DONE_WINDOW) return [];

            return {
                id,
                card,
                current,
                // Shoved off to the right, tilted and a little smaller, so a decision reads as the
                // mail being thrown onto a pile out of view rather than just vanishing.
                transform: done
                    ? `translate(calc(-50% + 100vw), -2rem) rotate(${10 + jitter(id, 4, 6)}deg) scale(0.85)`
                    : current
                        ? "translate(-50%, 0)"
                        : `translate(calc(-50% + ${jitter(id, 2, 10)}px), ${depth * 10 + jitter(id, 3, 6)}px)`
                            + ` rotate(${jitter(id, 1, 2.5)}deg)`,
                // Faded, not transparent: the cards behind have to stay solid.
                dim: current || done ? 0 : Math.min(0.3 + depth * 0.15, 0.8),
                // The cards behind can't be scrolled, so a long mail would only rasterise metres of
                // text nobody sees. Clipped to the box, which ends off the bottom of the window.
                clip: !current,
                // Carries the decision out with it, and washes back out if the mail is pulled back.
                tint: classificationTint(classification?.to),
                // A decided mail waits for its colour before it leaves: the decision should be
                // readable on the card, not a guess from the direction it flew off in.
                delay: done ? 150 : 0,
                // Leaving mails pass over the stack, the current one covers the rest.
                z: done ? 100 : current ? 50 : 49 - depth,
            };
        });
    });
</script>

<!-- Every card is positioned against this box, so a long mail never grows the stack. The box runs
     all the way to the bottom of the page and the cards keep a `pb-40` of slack instead: that puts
     the hard edge of the scroll clip behind the shortcut bar's blur, where a card's rounded corner
     and shadow don't get sliced mid-air. -->
<div class={cn("relative isolate w-3xl", className)}>
    <!-- One markup branch for every state, only the classes and the transform change: swapping
         elements per state would drop the transition exactly when a card moves. -->
    {#each stack as { id, card, current, transform, dim, tint, clip, delay, z } (id)}
        <!-- Transform and opacity only, and no filter anywhere on a card: the cards behind the
             current one used to carry a 1px blur, which is barely visible under the dimming but
             gives every one of them its own render surface - and those surfaces are re-applied
             every time the shortcut bar below needs its backdrop. Dimming alone puts them back.

             will-change puts every card on its own compositor layer for good, not just while it
             moves. The cards are metre-high boxes of text with a shadow, and everything that
             resizes the area around them - the sidebar sliding in and out above all - moves the
             stack sideways a pixel at a time. On a layer that is a move; without one it is five
             full repaints per frame. -->
        <div
                class="absolute inset-y-0 left-1/2 px-8 pt-10 transition-transform
                       duration-500 ease-out will-change-transform motion-reduce:transition-none
                       {current ? 'overflow-y-auto pb-40' : 'pointer-events-none'}"
                style="z-index: {z}; transform: {transform}; transition-delay: {delay}ms"
                aria-hidden={current ? undefined : "true"}
        >
            <EmailCard {...card} {dim} {tint} class={clip ? "max-h-full overflow-hidden" : undefined} />
        </div>
    {/each}
</div>
