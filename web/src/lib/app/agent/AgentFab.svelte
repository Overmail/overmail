<script lang="ts">
    // The shut state of the card: the way into the panel, and nothing else.
    import {SparkleIcon} from "phosphor-svelte";
    import {Button} from "$lib/components/ui/button";

    let {onclick, expanded}: {
        onclick: () => void;
        expanded: boolean;
    } = $props();
</script>

<Button
        variant="secondary"
        aria-label="Agent"
        aria-expanded={expanded}
        {onclick}
        class="flex flex-row items-center h-12 min-w-12 w-fit drop-shadow-xl rounded-4xl px-3.5 bg-background"
>
    <SparkleIcon class="size-6" />
</Button>
