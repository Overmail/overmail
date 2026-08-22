<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import type {MailTag} from "$lib/repository/MailRepository";

    let {tags}: { tags: MailTag[] } = $props();
</script>

{#if tags.length === 0}
    <span class="text-muted-foreground">—</span>
{:else}
    <!-- One line, clipped by the column: the row height is fixed, so tags must not wrap. -->
    <span class="flex gap-1 overflow-hidden">
        {#each tags as tag (tag.id)}
            <Badge variant="secondary">{tag.name}</Badge>
        {/each}
    </span>
{/if}
