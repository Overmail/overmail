<!--
    Throwaway harness: renders EmailPreviewCard for the shapes a mail comes in -- an html mail, a
    plain text one, one with nothing readable in it, a subject past two lines, more labels than fit,
    a sender without a display name. The body endpoint is stubbed, so this needs neither a running
    server nor data in the database.
    It lives under /auth because the root layout locks everything else behind the session.
-->
<script lang="ts">
    import {browser} from "$app/environment";
    import EmailPreviewCard from "$lib/app/mails/EmailPreviewCard.svelte";
    import type {EmailLabel, EmailMeta, EmailParticipant} from "$lib/repository/EmailRepository.svelte";

    const BODY_PATH = /^\/api\/emails\/(.+)\/body$/;

    /** What the stubbed endpoint answers per mail id. */
    const bodies: Record<string, {text: string | null; html: string | null}> = {
        html: {
            text: null,
            html: `<div style="font-family: sans-serif; width: 640px">
                <div style="background: #1d4ed8; color: white; padding: 32px">
                    <h1 style="margin: 0">Deine Bestellung ist unterwegs</h1>
                </div>
                <div style="padding: 32px; font-size: 15px; line-height: 1.6; color: #111">
                    <p>Hallo Julius,</p>
                    <p>
                        wir haben dein Paket heute Morgen an den Versanddienstleister übergeben. Die
                        Sendungsnummer ist <b>DE9917364512</b>, damit kannst du die Zustellung jederzeit
                        verfolgen. Voraussichtliche Lieferung: morgen zwischen 10 und 14 Uhr.
                    </p>
                    <p>Das ist die zweite Absatzlänge, die im Kartenausschnitt schon abgeschnitten wird.</p>
                    <p>Viele Grüße<br/>Dein Shop</p>
                </div>
            </div>`,
        },
        text: {
            text: "Hi Julius,\n\nkurze Rückfrage zum Termin am Donnerstag: passt 14 Uhr bei dir, oder\nsollen wir auf Freitag ausweichen? Die Unterlagen habe ich schon\nvorbereitet und hänge sie gleich an.\n\nDanach kommt noch mehr Text, der im Ausschnitt nicht mehr zu sehen ist.\n\nBeste Grüße\nMarie",
            html: null,
        },
        empty: {text: null, html: null},
    };

    // Before the cards mount, so their first fetch already lands here.
    if (browser) {
        const real = window.fetch;
        window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
            const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
            const match = BODY_PATH.exec(new URL(url, location.origin).pathname);
            const body = match ? bodies[match[1].split("-")[0]] : undefined;
            if (!body) return real(input, init);

            return Promise.resolve(new Response(JSON.stringify(body), {
                headers: {"content-type": "application/json"},
            }));
        }) as typeof window.fetch;
    }

    function participant(name: string | null, address: string): EmailParticipant {
        return {id: address, name, address, avatarUrl: null, avatarPadding: null};
    }

    function label(name: string, color: string): EmailLabel {
        return {
            id: name,
            name,
            color,
            description: null,
            assignmentReason: null,
            createdByAgent: false,
        };
    }

    const days = (n: number) => Math.floor(Date.now() / 1000) - n * 86_400;

    function mail(id: string, over: Partial<EmailMeta>): EmailMeta {
        return {
            id,
            subject: "Deine Bestellung ist unterwegs",
            sent: days(0),
            isRead: true,
            preview: null,
            archiveState: "unarchive",
            sender: participant("Shop Versand", "versand@shop.example"),
            to: [],
            cc: [],
            bcc: [],
            labels: [],
            ...over,
        };
    }

    const mails: EmailMeta[] = [
        mail("html-1", {labels: [label("Bestellungen", "#2563eb")]}),
        mail("text-1", {
            subject: "Termin Donnerstag?",
            sent: days(3),
            sender: participant("Marie Ludwig", "marie@example.org"),
            labels: [label("Arbeit", "#16a34a"), label("Wartet auf mich", "#f59e0b")],
        }),
        mail("empty-1", {
            subject: "",
            sent: days(400),
            sender: participant(null, "no-reply@newsletter.example"),
        }),
        mail("html-2", {
            subject: "Ein Betreff, der so lang ist, dass er die beiden Zeilen der Karte voll ausnutzt und danach abgeschnitten werden muss",
            sent: days(1),
            labels: [
                label("Bestellungen", "#2563eb"),
                label("Rechnungen", "#db2777"),
                label("Archiv", "#6b7280"),
                label("Später", "#7c3aed"),
                label("Reisen", "#0891b2"),
            ],
        }),
    ];
</script>

<div class="flex flex-row flex-wrap items-start gap-6 p-8">
    {#each mails as item (item.id)}
        <EmailPreviewCard mail={item} class="w-80"/>
    {/each}
</div>
