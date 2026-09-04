<script lang="ts">
    import EmailCard from "$lib/app/my-stack/EmailCard.svelte";
    import {cn} from "$lib/utils.js";
    import type {StackEmail} from "$lib/app/my-stack/EmailStackViewModel.svelte";

    let {
        emails,
        currentEmailId,
        onRequestReclassify,
        class: className,
    }: {
        /** Newest first: `emails[0]` is the card on top. */
        emails: StackEmail[];
        currentEmailId: string | null;
        onRequestReclassify: (email: StackEmail) => Promise<boolean>;
        class?: string;
    } = $props();

    /** How far down the stack we still offset a card; deeper ones share the last position. */
    const MAX_DEPTH = 4;

    /** How many handled mails stay mounted behind the current one, so their exit can play out. */
    const KEEP_DONE = 2;

    /** Long enough for the last sheet to have landed, including its stagger. */
    const INTRO_DURATION = 1200;

    /**
     * The mails whose card is laid out and may be shown. A card is held back until then: the body
     * is an iframe and an iframe has no height before it has measured itself, so a mail that goes
     * up right away is a header that then grows a body under the reader -- and the stack would
     * fan out a batch of cards that are still empty.
     */
    let readyIds = $state(new Set<string>());

    function onCardReady(id: string) {
        if (readyIds.has(id)) return;

        readyIds = new Set(readyIds).add(id);
    }

    /**
     * The mails of the very first batch, which are the ones that get laid down. The set empties
     * again once the animation is over, so a card that only scrolls into the rendered window
     * later -- a mail from the first batch that was too deep to be mounted, or a mail from a
     * later batch -- appears in the pile instead of being dealt onto it a second time.
     */
    let introIds = $state(new Set<string>());
    /** Whether the fan has been let go, which is what starts the animation. */
    let laidDown = $state(false);
    let introStarted = false;

    $effect(() => {
        if (introStarted || emails.length === 0) return;
        introStarted = true;

        introIds = new Set(emails.slice(0, MAX_DEPTH + 1).map((email) => email.id));
    });

    // The whole batch goes down together, so it waits for the slowest body of the batch. Nothing
    // guards against that never happening, because the card reports itself ready on a timeout
    // rather than leaving the stack hanging on a mail that will not lay out.
    $effect(() => {
        if (laidDown || introIds.size === 0) return;

        for (const id of introIds) {
            if (!readyIds.has(id)) return;
        }

        laidDown = true;
    });

    $effect(() => {
        if (!laidDown || introIds.size === 0) return;

        const timer = setTimeout(() => (introIds = new Set()), INTRO_DURATION);
        return () => clearTimeout(timer);
    });

    /**
     * Where a card of the first batch comes in from: the fan is pushed in from below the window,
     * opened wider the further back the sheet sits, and closes as it comes up into the stack.
     * Only the offset from the resting position is described here -- the animation ends at no
     * transform at all, so wherever the stack has arranged the card is where it lands.
     */
    function laid(id: string, depth: number) {
        return {
            // Viewport units, and past the full height of one: the sheets are pushed in from
            // outside the window, so they have to start outside it however tall it is.
            y: 105 + depth * 10 + jitter(id, 6, 5),
            // Every other sheet to the other side, so the fan opens around the middle instead of
            // leaning off to one side, and wider towards the back.
            rotate: (depth % 2 === 0 ? -1 : 1) * (7 + depth * 4) + jitter(id, 7, 2),
            // Back to front: the bottom of the pile is put down first and the sheet you are meant
            // to read lands last, on top of it.
            delay: (MAX_DEPTH - depth) * 70,
        };
    }

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
            return `translate(${-reach}vw, ${40 + jitter(id, 4, 20)}px) rotate(${-12 + spin}deg)`;
        }

        return `translate(${jitter(id, 4, 30)}px, ${reach}vh) rotate(${spin}deg)`;
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
                // Null until the fan is let go, so a card that is only waiting for the rest of
                // the batch carries no animation at all.
                const lay = introIds.has(id) && laidDown ? laid(id, depth) : null;

                return {
                    id,
                    email,
                    active,
                    lay,
                    // A card of the first batch is part of the fan and appears with it, not
                    // before: the batch is one movement, not five cards popping in.
                    shown: readyIds.has(id) && (laidDown || !introIds.has(id)),
                    // The current card stays straight where the box already is; everything below
                    // it drifts. Nothing here centres the card -- that is the layout's job, see
                    // the wrapper below, and a transform doing it as well would put every card's
                    // box half a card away from where the card is drawn.
                    transform: gone ?? (active
                        ? "translate(0, 0)"
                        : `translate(${jitter(id, 2, 10)}px, ${depth * 10 + jitter(id, 3, 6)}px) rotate(${jitter(id, 1, 2.5)}deg)`),
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
     reading as separate cards.

     The column is a card of 48rem with 2rem of shadow room on either side, and it gives way on a
     screen too narrow for that rather than pushing past the edge -- the cards are laid out
     against this box, so whatever it is wide is what they are wide. -->
<div class={cn("relative isolate w-full max-w-[52rem]", className)}>
    <!-- One wrapper shape for every card, current or not: moving up the stack or off it only
         changes style, so the same DOM node survives and the transition can run. Splitting the
         cases into two branches would tear the node down and rebuild it, and the card would
         teleport. -->
    {#each virtualizedStack as { id, email, active, transform, fade, opacity, blur, z, lay, shown } (id)}
        <!-- Two boxes rather than one, because a card can be in two movements at once and they
             do not compose in a single transform: the outer one is only ever the sheet being
             pushed in when the stack first arrives, the inner one holds the place in the pile and
             slides between places from then on. The height sits out here, since that is what the
             card is positioned against.

             The box is simply the column, which is what the card fills -- no width of its own and
             nothing centring it. A transform would move the card but leave its box behind, and
             the box of a card that is off to the side or already gone would then sit over the one
             you are reading and swallow the clicks meant for it; the menu in its top right corner
             is exactly there.

             Only the current card takes clicks at all, and that is decided here rather than on
             the scroll box, so it covers the card, its tint and the empty space around them in
             one go. -->
        <div
                class={cn(
                    "absolute inset-0",
                    !active && "pointer-events-none",
                    lay && "lay-down",
                )}
                style="z-index: {z};{lay ? ` --lay-y: ${lay.y}vh; --lay-rotate: ${lay.rotate}deg; animation-delay: ${lay.delay}ms;` : ''}"
                aria-hidden={!active}
        >
            <!-- The scroll box goes around the card, not inside it: the card keeps its natural
                 height and the box slides it, so a long mail moves as one object — background,
                 header and shadow together — instead of the body sliding under a header that
                 stays put.

                 h-full is what makes that work at all: only a box with a height of its own can
                 overflow. The horizontal padding is what the drop shadow lives in, since a scroll
                 box clips -- overflow in one axis makes the other one clip too. pb-32 matches the
                 shortcut bar: the box reaches the bottom of the page so the card disappears under
                 the bar, and that padding is extra scroll range at the end, which brings the last
                 lines back out from under it.

                 The cards that are not current keep the same overflow rather than switching to
                 hidden: the wrapper already stops them from eating the wheel, and a card leaving
                 the stack would otherwise snap back to its top mid-flight. -->
            <div
                    class={cn(
                        "card-scroll h-full w-full overflow-y-auto overscroll-contain px-4 pt-2 pb-32 sm:px-8",
                        "transition-[transform,opacity,filter] duration-500 ease-out motion-reduce:transition-none",
                    )}
                    style="transform: {transform}; opacity: {opacity};{blur ? ` filter: blur(${blur}px);` : ''}"
            >
                <!-- Wraps the card alone, so the tint below lines up with it instead of with the
                     full-height scroll box. Hiding the card happens here rather than on the card
                     itself, so the tint that belongs to it goes with it -- it is opaque enough to
                     show as a rectangle over nothing. -->
                <div class={cn("relative", !shown && "invisible")}>
                    <EmailCard
                            {...email}
                            onRequestReclassify={() => onRequestReclassify(email)}
                            onReady={() => onCardReady(id)}
                    />
                    <div
                            class="pointer-events-none absolute inset-0 rounded-2xl bg-background transition-opacity duration-500 motion-reduce:transition-none"
                            style="opacity: {fade}"
                    ></div>
                </div>
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

    /* The first mails do not fade in, they are pushed in: the batch comes up from below the
       window as an open fan and every sheet swings shut onto the pile. The origin is what makes
       it a fan rather than five cards drifting in -- they all turn around the same point, the
       bottom middle, so the angles read as one hand opening. It only applies while the animation
       runs, since the animation ends at no transform at all.

       Nothing here touches the opacity: the sheets start outside the window, so being pushed in
       is the whole appearance, and `both` holds a sheet whose turn has not come yet out there
       rather than letting it sit finished in the stack until its delay is up. The deceleration
       curve is the push: fast off the bottom edge, a hair past the resting spot, settled. */
    .lay-down {
        transform-origin: bottom center;
        animation: lay-down 750ms cubic-bezier(0.2, 1.04, 0.32, 1) both;
    }

    @keyframes lay-down {
        from {
            transform: translateY(var(--lay-y)) rotate(var(--lay-rotate));
        }

        to {
            transform: none;
        }
    }

    @media (prefers-reduced-motion: reduce) {
        .lay-down {
            animation: none;
        }
    }
</style>
