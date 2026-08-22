<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import type {MailTag} from "$lib/repository/MailRepository";
    import {tagColor} from "../tagColor";

    /** Past this the row is more badge than subject; the rest is a count. */
    const MAX_BADGES = 3;

    let {tags}: { tags: MailTag[] } = $props();

    const shown = $derived(tags.slice(0, MAX_BADGES));
    const hidden = $derived(tags.length - shown.length);
</script>

<!--
    The tag's own colour, mixed into the surface rather than used as one: the palette is derived
    from the tag name and knows nothing about the theme, so a flat fill would be unreadable in one
    of them. The text carries the hue, the background only hints at it.
-->
{#each shown as tag (tag.id)}
    <Badge
            variant="ghost"
            class="shrink-0 bg-(--tag)/10 font-normal text-(--tag)"
            style="--tag: {tagColor(tag.name)}"
            title={tag.name}
    >
        {tag.name}
    </Badge>
{/each}

{#if hidden > 0}
    <span class="text-muted-foreground shrink-0 text-xs" title={tags.map((it) => it.name).join(', ')}>
        +{hidden}
    </span>
{/if}
