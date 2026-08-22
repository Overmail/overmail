<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import ArrowsClockwise from "phosphor-svelte/lib/ArrowsClockwise";
    import ImageBroken from "phosphor-svelte/lib/ImageBroken";
    import {avatarStore} from "$lib/app/avatars/AvatarStore.svelte";

    // The section is the one place the cache is operated from, so it is also what reads it: the
    // table only ever looks pictures up.
    $effect(() => avatarStore.ensureLoaded());

    const missing = $derived(Math.max(0, avatarStore.addressesTotal - avatarStore.coveredCount));

    const progress = $derived(avatarStore.refresh);
    /** Which button started what is running, so only that one reads as busy. */
    const running = $derived(avatarStore.isRefreshing ? (progress?.all ? 'all' : 'missing') : null);
</script>

<section class="flex flex-col items-start gap-3">
    <div class="flex flex-col gap-0.5">
        <h2 class="text-sm font-medium">Avatare</h2>
        <p class="text-muted-foreground max-w-prose text-xs">
            Lädt die Bilder deiner Kontakte herunter: das Logo, das eine Domain per BIMI selbst
            angibt, und sonst die Marke aus der eingebauten Liste. Läuft nur auf Knopfdruck, denn
            jede Adresse ist eine Anfrage an einen Dritten.
        </p>
    </div>

    <div class="flex flex-wrap items-center gap-2">
        <!-- The cheap one first: it leaves every picture we already have untouched. -->
        <Button
                variant="outline"
                size="sm"
                disabled={avatarStore.isRefreshing}
                onclick={() => avatarStore.startRefresh(false)}
        >
            <ImageBroken data-icon="inline-start" />
            Fehlende probieren
        </Button>

        <Button
                variant="ghost"
                size="sm"
                disabled={avatarStore.isRefreshing}
                onclick={() => avatarStore.startRefresh(true)}
        >
            <ArrowsClockwise data-icon="inline-start" />
            Cache auffrischen
        </Button>
    </div>

    {#if avatarStore.failed}
        <p class="text-destructive text-xs">Die Avatare konnten nicht geladen werden.</p>
    {:else if running}
        <!-- The downloads run server side; the list is read again while they do, so the pictures
             in the table below fill in on their own. -->
        <p class="text-muted-foreground text-xs tabular-nums">
            {running === 'all' ? 'Alle' : 'Fehlende'} Adressen: {progress?.done ?? 0} von
            {progress?.total ?? 0} geprüft, {progress?.found ?? 0} gefunden…
        </p>
    {:else if avatarStore.loaded}
        <p class="text-muted-foreground text-xs tabular-nums">
            {avatarStore.coveredCount} von {avatarStore.addressesTotal} Adressen haben ein Bild,
            {missing} {missing === 1 ? 'fehlt' : 'fehlen'}.
            {#if progress}
                Letzter Durchlauf: {progress.found} von {progress.total} gefunden.
            {/if}
        </p>
    {/if}
</section>
