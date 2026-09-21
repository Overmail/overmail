<!--
    Throwaway harness: renders MailBody for the shapes an html mail comes in, next to the theme
    switch, because what a mail brings along and what the app's theme is are the two things that
    decide whether the mail is readable. No request and no data needed.
    It lives under /auth because the root layout locks everything else behind the session.
-->
<script lang="ts">
    import MailBody from "$lib/app/mails/detail_panel/MailBody.svelte";
    import {Button} from "$lib/components/ui/button";
    import {mode, toggleMode} from "mode-watcher";

    /** The shapes that decide the question, each named after what the mail does about colour. */
    const mails: {name: string; html: string | null; text?: string | null}[] = [
        {
            name: "text colour, no background",
            html: `<div style="font-family: Arial, sans-serif; font-size: 15px; color: #333">
                <p>Hallo Julius,</p>
                <p>dein Termin am Donnerstag ist bestätigt. Wir sehen uns um 14 Uhr.</p>
                <p style="color: #666">Diese Mail wurde automatisch erzeugt.</p>
            </div>`,
        },
        {
            name: "own background, no text colour",
            html: `<table width="100%" style="background: #ffffff; font-family: Arial, sans-serif">
                <tr><td style="padding: 24px">
                    <h2>Deine Rechnung</h2>
                    <p>Der Betrag von 24,90 € wurde abgebucht.</p>
                </td></tr>
            </table>`,
        },
        {
            name: "fully branded",
            html: `<div style="font-family: sans-serif">
                <div style="background: #1d4ed8; color: white; padding: 32px">
                    <h1 style="margin: 0">Deine Bestellung ist unterwegs</h1>
                </div>
                <div style="padding: 32px; font-size: 15px; line-height: 1.6; color: #111">
                    <p>Sendungsnummer <b>DE9917364512</b>, Lieferung morgen zwischen 10 und 14 Uhr.</p>
                    <a href="https://example.org" style="color: #1d4ed8">Sendung verfolgen</a>
                </div>
            </div>`,
        },
        {
            name: "a mail that is dark itself",
            html: `<div style="background: #0b0b0f; color: #e5e7eb; padding: 32px; font-family: sans-serif">
                <h2 style="margin: 0 0 12px; color: #a78bfa">Release 2.4</h2>
                <p style="margin: 0">Dieser Newsletter ist selbst dunkel und muss es bleiben.</p>
            </div>`,
        },
        {
            name: "no colours at all",
            html: `<p>Kurze Mail ganz ohne Farben. Sie nimmt, was der Client ihr gibt.</p>`,
        },
        {
            name: "html and text",
            html: `<p style="color: #222">Beide Teile sind da; der Text-Tab bleibt im Thema der App.</p>`,
            text: "Beide Teile sind da; der Text-Tab bleibt im Thema der App.",
        },
    ];
</script>

<div class="flex flex-col gap-8 p-8">
    <div class="flex flex-row items-center gap-3">
        <Button variant="outline" onclick={toggleMode}>Theme wechseln</Button>
        <span class="text-sm text-muted-foreground">{mode.current}</span>
    </div>

    <div class="flex flex-row flex-wrap items-start gap-8">
        {#each mails as item (item.name)}
            <div class="flex w-[420px] flex-col gap-2 rounded-lg border bg-card p-4 text-card-foreground">
                <span class="text-xs text-muted-foreground">{item.name}</span>
                <MailBody text={item.text ?? null} html={item.html}/>
            </div>
        {/each}
    </div>
</div>
