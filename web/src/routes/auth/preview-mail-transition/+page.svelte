<!--
    Throwaway harness: the two ends of the mail morph -- the panel beside the list and the mail on
    its own page -- in one route, with the view transition between them run by hand, slowed down
    and scrubbable. Repositories are stubbed, so this needs neither a running server nor data.

    It lives under /auth because the root layout locks everything else behind the session. That
    also means no app shell here, so the header and the navbar are stand-ins with the shell's
    measurements: the morph is a matter of where things are, and the shell is what puts them there.

    Driven from the page -- the buttons, the speed, the scrubber -- or from a script:
    `window.__mailTransition` has go(view), seek(fraction), resume(), finish(), describe() and a
    `speed` setter, which is how the frames of the morph were shot and compared while tuning it.
-->
<script lang="ts">
    import {tick} from "svelte";
    import {createRepositories, provideRepositories} from "$lib/repository/repositories";
    import type {EmailLabel, EmailMeta, EmailParticipant} from "$lib/repository/EmailRepository.svelte";
    import type {EmailRepository} from "$lib/repository/EmailRepository.svelte";
    import type {EmailBodyRepository} from "$lib/repository/EmailBodyRepository";
    import type {CurrentUserRepository} from "$lib/repository/CurrentUserRepository";
    import * as Tooltip from "$lib/components/ui/tooltip";
    import MailDetailPanel from "$lib/app/mails/detail_panel/MailDetailPanel.svelte";
    import MailPage from "$lib/app/mails/MailPage.svelte";
    import {startMorph} from "$lib/app/mails/mailViewTransition";

    /* ---------- the data ---------- */

    function participant(name: string | null, address: string): EmailParticipant {
        return {id: address, name, address, avatarUrl: null, avatarPadding: null};
    }

    function label(name: string, color: string): EmailLabel {
        return {id: name, name, color, description: null, assignmentReason: null, createdByAgent: false};
    }

    const now = Math.floor(Date.now() / 1000);

    const open: EmailMeta = {
        id: "0b7f1d2c-3e4a-5b6c-7d8e-9f0a1b2c3d4e",
        subject: "Rückfrage zum Termin am Donnerstag",
        sent: now - 3 * 3600,
        isRead: true,
        preview: null,
        archiveState: "unarchive",
        sender: participant("Marie Ludwig", "marie@example.org"),
        to: [participant("Julius Babies", "julius@example.org")],
        cc: [],
        bcc: [],
        labels: [label("Arbeit", "#16a34a"), label("Wartet auf mich", "#f59e0b")],
    };

    /** The rows behind the panel; only the one that is open is a real mail. */
    const rows = [
        {sender: "Shop Versand", subject: "Deine Bestellung ist unterwegs", sent: "09:12"},
        {sender: "Marie Ludwig", subject: open.subject, sent: "08:40", open: true},
        {sender: "Uni Mail", subject: "Fristen für das Wintersemester", sent: "Gestern"},
        {sender: "Buchhaltung", subject: "Rechnung 2026-0912", sent: "Gestern"},
        {sender: "no-reply@newsletter.example", subject: "Was diese Woche wichtig war", sent: "Mo"},
        {sender: "Tim Berger", subject: "Re: Slides für Freitag", sent: "Mo"},
        {sender: "Bahn", subject: "Dein Ticket nach Berlin", sent: "So"},
        {sender: "Marie Ludwig", subject: "Unterlagen", sent: "Sa"},
    ];

    const body = {
        text: null,
        html: `<div style="font-family: sans-serif; font-size: 15px; line-height: 1.6; color: #111; max-width: 560px">
            <p>Hi Julius,</p>
            <p>kurze Rückfrage zum Termin am Donnerstag: passt 14 Uhr bei dir, oder sollen wir auf
            Freitag ausweichen? Die Unterlagen habe ich schon vorbereitet und hänge sie gleich an.</p>
            <p>Danach noch der übliche Absatz, der nur da ist, damit der Body eine Länge hat und man
            sieht, wie sich der Inhalt beim Übergang verhält.</p>
            <p>Beste Grüße<br/>Marie</p>
        </div>`,
    };

    /* ---------- the stubs ---------- */

    const mails = {
        peek: () => ({value: open, isLoading: false}),
        subscribe: () => () => {},
        watchMoves: () => () => {},
        revision: 0,
        setRead: async () => {},
        setArchiveState: async () => {},
        attachLabel: async () => {},
        detachLabel: async () => {},
        createLabelOn: async () => {},
        requestClassification: async () => true,
    } as unknown as EmailRepository;

    const emailBody = {getBody: async () => body} as unknown as EmailBodyRepository;

    const currentUser = {
        get: async () => ({
            id: "me",
            firstname: "Julius",
            lastname: "Babies",
            address: "julius@example.org",
            addresses: ["julius@example.org"],
        }),
        forget: () => {},
    } as unknown as CurrentUserRepository;

    provideRepositories(createRepositories({mails, emailBody, currentUser}));

    /* ---------- the transition ---------- */

    type View = "list" | "page";

    let view: View = $state("list");
    let speed = $state(0.1);
    let progress = $state(0);
    let supported = $state(true);
    let running = $state(false);

    /** The animations of the current transition, held while it runs so they can be scrubbed. */
    let held: Animation[] = [];

    /**
     * The view transition itself. What the layout does through onNavigate, done by hand: the
     * state flips inside the update callback, between the two snapshots.
     */
    async function go(target: View) {
        if (target === view) return;

        const transition = startMorph(async () => {
            view = target;
            await tick();
        });
        if (transition === null) {
            supported = false;
            view = target;
            return;
        }
        running = true;

        await transition.ready;
        held = document.getAnimations().filter((animation) => {
            const effect = animation.effect as KeyframeEffect | null;
            return effect?.pseudoElement?.startsWith("::view-transition") ?? false;
        });
        for (const animation of held) animation.playbackRate = speed;

        await transition.finished.catch(() => {});
        running = false;
        held = [];
        progress = 0;
    }

    /** Freezes the running transition at a point between 0 and 1 of its length. */
    function seek(fraction: number) {
        progress = fraction;
        for (const animation of held) {
            const timing = (animation.effect as KeyframeEffect).getComputedTiming();
            const total = Number(timing.delay ?? 0) + Number(timing.duration ?? 0);
            animation.pause();
            animation.currentTime = fraction * total;
        }
    }

    function resume() {
        for (const animation of held) animation.play();
    }

    /** Jumps to the end, so the next run can start. */
    function finish() {
        for (const animation of held) animation.finish();
    }

    /** What the browser is animating: one line per pseudo-element, for a script to read. */
    function describe() {
        return held.map((animation) => {
            const effect = animation.effect as KeyframeEffect;
            const timing = effect.getComputedTiming();
            return {
                pseudo: effect.pseudoElement,
                duration: timing.duration,
                delay: timing.delay,
                easing: effect.getTiming().easing,
                keyframes: effect.getKeyframes().map((frame) => {
                    const {composite: _c, computedOffset: _o, easing: _e, ...rest} = frame as Record<string, unknown>;
                    return rest;
                }),
            };
        });
    }

    $effect(() => {
        (window as unknown as {__mailTransition: unknown}).__mailTransition = {
            go,
            seek,
            resume,
            finish,
            describe,
            get held() {
                return held;
            },
            get view() {
                return view;
            },
            set speed(value: number) {
                speed = value;
            },
        };
    });
</script>

<!-- The shell's measurements: a 12-high header over a full-height content column, and the
     panel's offset variable that the real layout sets. The tooltip provider is the shell's too. -->
<Tooltip.Provider delayDuration={0}>
<div class="flex min-h-svh flex-col" style="--panel-offset: 0px; --panel-duration: 0ms">
    <header class="sticky top-0 z-30 flex h-12 shrink-0 items-center gap-2 border-b bg-background px-6">
        <span class="text-base font-medium">Postfach</span>

        <!-- The harness' own controls, which are not part of either view and so not of the morph. -->
        <div class="ms-auto flex items-center gap-2 text-xs" style="view-transition-name: harness-controls">
            <button class="rounded border px-2 py-1" onclick={() => go("page")} disabled={view === "page"}>→ Seite</button>
            <button class="rounded border px-2 py-1" onclick={() => go("list")} disabled={view === "list"}>← Liste</button>
            <select class="rounded border px-1 py-1" bind:value={speed}>
                <option value={1}>1×</option>
                <option value={0.5}>0.5×</option>
                <option value={0.25}>0.25×</option>
                <option value={0.1}>0.1×</option>
            </select>
            <input
                    type="range" min="0" max="1" step="0.01" value={progress}
                    disabled={!running}
                    oninput={(event) => seek(Number(event.currentTarget.value))}
            />
            <span class="w-8 tabular-nums">{Math.round(progress * 100)}%</span>
            {#if !supported}<span class="text-destructive">no startViewTransition</span>{/if}
        </div>
    </header>

    {#if view === "list"}
        <div class="flex flex-col">
            <div class="px-16 pt-16 pb-8">
                <div class="font-display text-3xl">Guten Morgen, Julius</div>
                <div class="text-muted-foreground">8 Mails im Postfach</div>
            </div>

            <div class="px-4 pb-16">
                <table class="w-full table-fixed text-sm text-muted-foreground">
                    <tbody>
                    {#each rows as row (row.subject)}
                        <tr class="h-10 border-0" class:bg-muted={row.open}>
                            <td class="w-72 truncate px-2">{row.sender}</td>
                            <td class="truncate px-2 text-foreground">{row.subject}</td>
                            <td class="w-16 px-2 text-end">{row.sent}</td>
                        </tr>
                    {/each}
                    </tbody>
                </table>
            </div>
        </div>

        <MailDetailPanel
                id={open.id}
                canStepUp={true}
                canStepDown={true}
                onStep={() => {}}
                onClose={() => {}}
        />
    {:else}
        <MailPage id={open.id} cameFromMailList={true} onBack={() => go("list")}/>
    {/if}
</div>
</Tooltip.Provider>
