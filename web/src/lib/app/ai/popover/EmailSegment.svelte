<script lang="ts">
    import {EnvelopeSimpleIcon, TagIcon} from "phosphor-svelte";

    let {
        emailId,
    }: {
        emailId: string;
    } = $props();
</script>

<span class="inline whitespace-nowrap bg-card text-card-foreground px-1 mx-0.5 rounded outline">
    <EnvelopeSimpleIcon class="inline size-3.5 align-[-0.2em]"/>
    {emailId}
</span>