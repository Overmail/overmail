export type StackIntroConfig = {
    /** How many cards the fan is made of, which is how deep the pile is drawn. */
    size: number;
    /** How long the fan waits for the rest of the first batch before it goes down regardless. */
    wait: number;
    /** How long the animation takes, its stagger included; the fan is over afterwards. */
    duration: number;
};

/** Nothing to show and nothing to wait for. */
const HELD_BACK = {shown: false, dealt: false};

/**
 * The first cards of the pile: which of them the fan is made of, when they may be shown, and when
 * they are dealt.
 *
 * Two things make this more than animating whatever is there when the stack arrives. A card is
 * not worth showing before it is laid out -- its body is an iframe, and an iframe has no height
 * until it has measured itself -- and the pile does not arrive as a batch: the ids come over the
 * stack socket, the metadata of every mail over another one, and every body over a request of its
 * own, so the head of the pile fills in a card at a time. Dealing what is there when the first
 * card can be drawn deals a single sheet and lets the rest turn up in the pile behind it, which
 * is what navigating into the stack looked like -- on a reload nothing was cached, so the batch
 * happened to become drawable in one go and the fan came out right.
 *
 * So the fan is held until nothing of the first batch is on its way any more, and let go once
 * every card in it is laid out. [StackIntroConfig.wait] is the backstop, for a mail whose body or
 * metadata never arrives at all.
 */
export class StackIntro {
    /**
     * The mails the fan is made of. Emptied once it has played out, so a card that only turns up
     * later -- one from a later batch, or one that was too deep to be mounted -- appears in the
     * pile instead of being dealt onto it a second time.
     */
    private ids: Set<string> = $state(new Set());

    /** The mails whose card is laid out, and so worth showing. */
    private laidOut: Set<string> = $state(new Set());

    /** Whether the fan has been let go, which is what starts the animation. */
    private released = $state(false);

    /** The head of the pile as [observe] last saw it; what [ids] is kept in step with. */
    private head: string[] = [];

    /** How many mails of the pile are still on their way, from the last [observe]. */
    private onTheirWay = 0;

    /** Whether the wait for the rest of the batch has run out. */
    private waitedOut = false;
    private waiting = false;

    private readonly timers = new Set<ReturnType<typeof setTimeout>>();

    constructor(private readonly config: StackIntroConfig) {}

    /**
     * The pile as it stands: the ids that can be drawn, newest first, and how many mails are
     * still on their way. From an effect -- it is what lets the fan go.
     */
    observe(ids: string[], onTheirWay: number) {
        this.onTheirWay = onTheirWay;
        // The fan is what it was when it went down; from then on the pile draws itself.
        if (this.released) return;

        const head = ids.slice(0, this.config.size);
        if (head.length !== this.head.length || head.some((id, index) => id !== this.head[index])) {
            this.head = head;
            this.ids = new Set(head);
        }

        if (head.length > 0) this.startWait();
        this.deal();
    }

    /** A card that has laid itself out, and so could be shown. */
    onReady(id: string) {
        if (this.laidOut.has(id)) return;

        this.laidOut = new Set(this.laidOut).add(id);
        this.deal();
    }

    /**
     * How the card of [id] is drawn: whether it may be shown at all, and whether it is coming in
     * with the fan right now, which is what carries the animation.
     *
     * A card of the fan appears with it and not before -- the batch is one movement, not five
     * cards popping in one after the other.
     */
    card(id: string): {shown: boolean; dealt: boolean} {
        if (!this.laidOut.has(id)) return HELD_BACK;
        if (!this.ids.has(id)) return {shown: true, dealt: false};

        return {shown: this.released, dealt: this.released};
    }

    dispose() {
        this.timers.forEach(clearTimeout);
        this.timers.clear();
    }

    /** Lets the fan go, once there is nothing left to wait for. */
    private deal() {
        if (this.released || this.ids.size === 0) return;
        // Still filling in: more mails are coming and there is room for them in the fan. A head
        // that is already as deep as the pile is drawn is complete whatever else is on its way.
        if (this.onTheirWay > 0 && this.ids.size < this.config.size && !this.waitedOut) return;
        // The whole batch goes down together, so it waits for the slowest body in it.
        for (const id of this.ids) {
            if (!this.laidOut.has(id)) return;
        }

        this.released = true;
        this.after(this.config.duration, () => (this.ids = new Set()));
    }

    /** The backstop, started with the first card that can be drawn. */
    private startWait() {
        if (this.waiting) return;

        this.waiting = true;
        this.after(this.config.wait, () => {
            this.waitedOut = true;
            this.deal();
        });
    }

    private after(delay: number, run: () => void) {
        const timer = setTimeout(() => {
            this.timers.delete(timer);
            run();
        }, delay);
        this.timers.add(timer);
    }
}
