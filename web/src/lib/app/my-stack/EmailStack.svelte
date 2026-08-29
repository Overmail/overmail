<script lang="ts">
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import {cn} from "$lib/utils.js";
    import type {StackEmail} from "$lib/app/my-stack/EmailStackViewModel.svelte";

    let {
        emails,
        currentEmailId,
        class: className,
    }: {
        /** Newest first: `emails[0]` is the card on top. */
        emails: StackEmail[];
        currentEmailId: string | null;
        class?: string;
    } = $props();

    /** How far down the stack we still offset a card; deeper ones share the last position. */
    const MAX_DEPTH = 4;

    /** How many handled mails stay mounted behind the current one, so their exit can play out. */
    const KEEP_DONE = 2;

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

    /**
     * Where a handled mail goes once it is off the stack. The direction is the confirmation of
     * what happened to it, so it has to match the shortcut that was pressed: archived mails are
     * swept off to the left, everything else drops down onto the pile — a mail that was passed
     * over without a decision stays in the mailbox, so it leaves the same way a kept one does.
     *
     * `steps` is how many mails ago it was handled — the ones further back travel further, so a
     * fast run through the stack does not pile them on top of each other.
     */
    function exit(email: StackEmail, steps: number): string {
        const id = email.id;
        const spin = jitter(id, 5, 6);
        // Viewport units rather than a share of the card: it has to clear the window, and the
        // window is what it has to clear.
        const reach = 100 + steps * 15;

        if (email.classification?.type === "archive") {
            return `translate(calc(-50% - ${reach}vw), ${40 + jitter(id, 4, 20)}px) rotate(${-12 + spin}deg)`;
        }

        return `translate(calc(-50% + ${jitter(id, 4, 30)}px), ${reach}vh) rotate(${spin}deg)`;
    }

    /**
     * Everything is measured from the current mail rather than from the head of the list, so the
     * one being worked on is the card in front no matter how far into the stack it sits. Only the
     * handful of cards around it is rendered; the rest is offscreen anyway.
     */
    const virtualizedStack = $derived.by(() => {
        // An unknown id means the stack has not been positioned yet, and the head of the list is
        // as good a guess as any.
        const currentIndex = Math.max(0, emails.findIndex((email) => email.id === currentEmailId));

        return emails
            .map((email, index) => ({email, position: index - currentIndex}))
            .filter(({position}) => position >= -KEEP_DONE && position <= MAX_DEPTH)
            .map(({email, position}) => {
                const id = email.id;
                const active = position === 0;
                const depth = Math.max(position, 0);

                const gone = position < 0 ? exit(email, -position) : null;

                return {
                    id,
                    email,
                    active,
                    // The current card stays straight and centred; everything below it drifts.
                    transform: gone ?? (active
                        ? "translate(-50%, 0)"
                        : `translate(calc(-50% + ${jitter(id, 2, 10)}px), ${depth * 10 + jitter(id, 3, 6)}px) rotate(${jitter(id, 1, 2.5)}deg)`),
                    // Faded via an opaque-card + translucent-overlay sandwich rather than opacity
                    // on the card itself: the cards below have to stay solid, or the whole stack
                    // shows through itself. Handled ones may go properly transparent, since there
                    // is nothing left behind them to show through.
                    fade: active ? 0 : Math.min(0.2 + depth * 0.15, 0.65),
                    opacity: position < 0 ? 0 : 1,
                    // Only what is out of focus gets blurred: a filter would rasterize the card
                    // you are actually reading and take the edge off its text.
                    blur: active ? 0 : position < 0 ? 2 : 1,
                    // Handled mails sweep across the pile on their way out, the rest is ordered
                    // front to back.
                    z: position < 0 ? 100 + position : 50 - position,
                };
            });
    });
</script>

<!-- Every card is positioned against this box, so a long mail never grows the stack. Nothing
     clips the cards themselves: the drop shadow has to stay visible, or the ones behind stop
     reading as separate cards. -->
<div class={cn("relative isolate w-3xl", className)}>
    <!-- One wrapper shape for every card, current or not: moving up the stack or off it only
         changes style, so the same DOM node survives and the transition can run. Splitting the
         cases into two branches would tear the node down and rebuild it, and the card would
         teleport. -->
    {#each virtualizedStack as { id, email, active, transform, fade, opacity, blur, z } (id)}
        <!-- The scroll box goes around the card, not inside it: the card keeps its natural height
             and the box slides it, so a long mail moves as one object — background, header and
             shadow together — instead of the body sliding under a header that stays put.

             inset-y-0 is what makes that work at all: only a box with a height of its own can
             overflow. w-fit keeps it hugging the card, px-8 is there because a scroll box clips
             and the drop shadow would go with it, and pb-32 matches the shortcut bar: the box
             reaches the bottom of the page so the card disappears under the bar, and that padding
             is extra scroll range at the end, which brings the last lines back out from under it.

             The cards that are not current keep the same overflow rather than switching to
             hidden: pointer-events-none already stops them from eating the wheel, and a card
             leaving the stack would otherwise snap back to its top mid-flight. -->
        <div
                class={cn(
                    "card-scroll absolute inset-y-0 left-1/2 w-fit overflow-y-auto overscroll-contain px-8 pt-2 pb-32",
                    "transition-[transform,opacity,filter] duration-500 ease-out motion-reduce:transition-none",
                    !active && "pointer-events-none",
                )}
                style="z-index: {z}; transform: {transform}; opacity: {opacity};{blur ? ` filter: blur(${blur}px);` : ''}"
                aria-hidden={!active}
        >
            <!-- Hugs the card, so the tint below lines up with it instead of with the full-height
                 scroll box. -->
            <div class="relative w-fit">
                <EmailCard {...email} />
                <div
                        class="pointer-events-none absolute inset-0 rounded-2xl bg-background transition-opacity duration-500 motion-reduce:transition-none"
                        style="opacity: {fade}"
                ></div>
            </div>
        </div>
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
