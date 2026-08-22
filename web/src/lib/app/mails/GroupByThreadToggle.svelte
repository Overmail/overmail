<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import {mailGrouping} from "./grouping.svelte";
    import {ChatsIcon} from "phosphor-svelte";
</script>

<!-- Grouping is a way of reading the same list, so it flips in place instead of reloading it. -->
<Button
        variant={mailGrouping.byThread ? 'secondary' : 'outline'}
        size="sm"
        aria-pressed={mailGrouping.byThread}
        onclick={() => mailGrouping.toggle()}
>
    <ChatsIcon data-icon="inline-start" />
    Nach Thread gruppieren
</Button>
