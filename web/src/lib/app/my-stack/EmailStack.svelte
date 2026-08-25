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
        /** Absent while the body's own request is still out, see `EmailCard`. */
        body?: string;
        /** The mail's HTML part, see `EmailCard`. */
        html?: string;
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

    /** The same for mails that were skipped over, which now leave to the side just as decided ones do. */
    const PASSED_WINDOW = 2;

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
            // Skipped over rather than decided on: the mail keeps its place in the list and comes
            // round again later, but it leaves the screen now just like a decided one does.
            const passed = !done && !current && index < currentPosition;

            // Only the mails still to come take one of the positions in front. Counting a skipped
            // one would spend the depth budget before the loop even reaches the current card, and
            // everything from there on -- the current card included -- would be culled below.
            if (!done && !current && !passed) depth += 1;

            // Past the last offset position a card is covered by the ones in front of it, so it is
            // left out of the DOM entirely rather than painted and animated for nothing.
            if (depth > MAX_DEPTH) return [];
            if (done && index < currentPosition - DONE_WINDOW) return [];
            if (passed && index < currentPosition - PASSED_WINDOW) return [];

            return {
                id,
                card,
                current,
                // Shoved off to the right, tilted and a little smaller, so a decision reads as the
                // mail being thrown onto a pile out of view rather than just vanishing.
                transform: done
                    ? `translate(100vw, -2rem) rotate(${10 + jitter(id, 4, 6)}deg) scale(0.85)`
                    // Centred by the `mx-auto` on the same box: nothing to translate, but the
                    // identity transform has to be written out so a card arriving from the pile
                    // has something to transition to.
                    : current
                        ? "translate(0, 0)"
                        // Out to the left, against the pile of decided mail: skipping also clears
                        // the card off the screen, but a mail that comes round again must not look
                        // like one that was filed away.
                        : passed
                            ? `translate(-100vw, 2rem) rotate(${-10 + jitter(id, 5, 6)}deg) scale(0.85)`
                            : `translate(${jitter(id, 2, 10)}px, ${depth * 10 + jitter(id, 3, 6)}px)`
                                + ` rotate(${jitter(id, 1, 2.5)}deg)`,
                // Faded, not transparent: the cards behind have to stay solid. The two that are on
                // their way out stay at full strength -- they are read while they leave.
                dim: current || done || passed ? 0 : Math.min(0.3 + depth * 0.15, 0.8),
                // The cards behind can't be scrolled, so a long mail would only rasterise metres of
                // text nobody sees. Clipped to the box, which ends off the bottom of the window.
                clip: !current,
                // Carries the decision out with it, and washes back out if the mail is pulled back.
                tint: classificationTint(classification?.to),
                // A decided mail waits for its colour before it leaves: the decision should be
                // readable on the card, not a guess from the direction it flew off in.
                delay: done ? 150 : 0,
                // Both kinds of leaving card pass over the stack rather than out from under it,
                // and the current one covers the rest.
                z: done ? 100 : passed ? 99 : current ? 50 : 49 - depth,
            };
        });
    });
</script>

<!-- Every card brings its own scroll container, and that container covers this whole box: the
     scrollbar of the mail being read then sits at the right edge of the content area instead of at
     the edge of a centred column. What the transform moves is the box inside the container, never
     the container itself -- rotating a scroll container tilts its scrollbar with it and sends the
     wheel off diagonally.

     The containers run all the way to the bottom of the page and the card inside keeps a `pb-40` of
     slack instead: that puts the hard edge of the scroll clip behind the shortcut bar's blur, where
     a card's rounded corner and shadow don't get sliced mid-air. -->
<div class={cn("relative isolate w-full", className)}>
    <!-- One markup branch for every state, only the classes and the transform change: swapping
         elements per state would drop the transition exactly when a card moves. -->
    {#each stack as { id, card, current, transform, dim, tint, clip, delay, z } (id)}
        <div
                class="absolute inset-0 {current ? 'overflow-y-auto overflow-x-hidden' : 'pointer-events-none'}"
                style="z-index: {z}"
                aria-hidden={current ? undefined : "true"}
        >
            <!-- Transform and opacity only, and no filter anywhere on a card: the cards behind the
                 current one used to carry a 1px blur, which is barely visible under the dimming but
                 gives every one of them its own render surface - and those surfaces are re-applied
                 every time the shortcut bar below needs its backdrop. Dimming alone puts them back.

                 will-change puts every card on its own compositor layer for good, not just while it
                 moves. The cards are metre-high boxes of text with a shadow, and everything that
                 resizes the area around them - the sidebar sliding in and out above all - moves the
                 stack sideways a pixel at a time. On a layer that is a move; without one it is five
                 full repaints per frame.

                 `h-full` on everything but the current card is what the card's `max-h-full` clip
                 measures against, and it also keeps the rotation turning about the middle of the
                 box rather than about the middle of however long that particular mail is. -->
            <div
                    class="mx-auto w-full max-w-3xl px-8 pt-10 transition-transform duration-500
                           ease-out will-change-transform motion-reduce:transition-none
                           {current ? 'pb-40' : 'h-full'}"
                    style="transform: {transform}; transition-delay: {delay}ms"
            >
                <EmailCard {...card} {dim} {tint} class={clip ? "max-h-full overflow-hidden" : undefined} />
            </div>
        </div>
    {/each}
</div>
