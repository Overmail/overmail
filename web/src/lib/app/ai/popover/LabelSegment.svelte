<script lang="ts">
    import {TagIcon} from "phosphor-svelte";
    import type {PromptLabel} from "./prompt";

    let {
        label,
    }: {
        label: PromptLabel;
    } = $props();
</script>

<span class="inline whitespace-nowrap bg-card text-card-foreground px-1 mx-0.5 rounded outline">
    <TagIcon class="inline size-3.5 align-[-0.2em]" color={label.color}/>
    {label.name}
</span>
