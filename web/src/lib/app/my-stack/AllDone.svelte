<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {HouseIcon} from "phosphor-svelte";
    import { _ } from "svelte-i18n";

    const titleKeys = [
        "myStack.allDone.titles.1",
        "myStack.allDone.titles.2",
        "myStack.allDone.titles.3",
        "myStack.allDone.titles.4",
        "myStack.allDone.titles.5",
    ];

    const subtitleKeys = [
        "myStack.allDone.subtitles.1",
        "myStack.allDone.subtitles.2",
        "myStack.allDone.subtitles.3",
        "myStack.allDone.subtitles.4",
        "myStack.allDone.subtitles.5",
    ];

    const title = $_(titleKeys[Math.floor(Math.random() * titleKeys.length)]);
    const subtitle = $_(subtitleKeys[Math.floor(Math.random() * subtitleKeys.length)]);
</script>

<div class="flex flex-col items-center justify-center gap-2 p-4 text-center">
    <div class="text-2xl font-bold">{title}</div>
    <div class="text-gray-600">{subtitle}</div>
    <Button href="/" class="mt-2" variant="secondary">
        <HouseIcon/>
        {$_("app.nav.home")}
    </Button>
</div>