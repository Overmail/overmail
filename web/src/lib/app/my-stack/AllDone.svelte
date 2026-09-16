<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {HouseIcon} from "phosphor-svelte";

    const titles = [
        "Alles erledigt!",
        "Du bist wunderbar!",
        "Das war's (für jetzt).",
        "Du hast es geschafft!",
        "Zeit für eine Pause!",
    ]

    const subtitles = [
        "Gönn' dir einen Kaffee und geh raus.",
        "Möge der Tag dir wohlgesonnen sein.",
        "Jetzt kannst du dich um die wichtigen Dinge kümmern.",
        "Ein leerer Posteingang fühlt sich doch gut an.",
    ]

    const title = titles[Math.floor(Math.random() * titles.length)];
    const subtitle = subtitles[Math.floor(Math.random() * subtitles.length)];
</script>

<div class="flex flex-col items-center justify-center gap-2 p-4 text-center">
    <div class="text-2xl font-bold">{title}</div>
    <div class="text-gray-600">{subtitle}</div>
    <Button href="/" class="mt-2" variant="secondary">
        <HouseIcon/>
        Startseite
    </Button>
</div>