<!--
    A label the agent put on a mail, or took off it again: <toolcall-label-email> and
    <toolcall-unlabel-email>, see LabelEmailTool.markup and UnlabelEmailTool.markup.

    One component for both, because the line is the same shape either way -- what differs is the
    word in front of it and which way the tag points.
-->
<script lang="ts">
    import {TagIcon, TagSimpleIcon} from "phosphor-svelte";
    import EntityEmail from "$lib/app/entities/EntityEmail.svelte";
    import EntityLabel from "$lib/app/entities/EntityLabel.svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {_} from "svelte-i18n";

    let {
        attributes,
        detached = false,
    }: {
        attributes?: Record<string, string>;
        /** The same line for the other direction: the label came off this mail. */
        detached?: boolean;
    } = $props();

    const labelId = $derived(attributeOf(attributes, "labelId") ?? "");
    const emailId = $derived(attributeOf(attributes, "emailId") ?? "");
</script>

{#if labelId !== "" && emailId !== ""}
        <!-- flex-wrap: the chips are one line each and cannot be cut, so a narrow panel gets a
         second line rather than a row that reaches past its edge. -->
    <span class="inline-flex max-w-full flex-wrap items-center gap-1.5 align-[-0.2em] text-muted-foreground">
        {#if detached}
            <TagSimpleIcon class="size-4 shrink-0"/>
        {:else}
            <TagIcon class="size-4 shrink-0"/>
        {/if}
        <span class="shrink-0">
            {$_(detached ? "ai.chat.messages.unlabelEmail" : "ai.chat.messages.labelEmail")}
        </span>
        <EntityLabel id={labelId}/>
        <!-- A separator rather than a word between the two: "on" and "from" would be sentence
             fragments to translate, and their place in the line is not the same everywhere. -->
        <span class="shrink-0" aria-hidden="true">&middot;</span>
        <EntityEmail id={emailId}/>
    </span>
{/if}
