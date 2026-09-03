<!--
    A label by id, filled from the entity repository. Rendered for the `<label id="...">` element
    the agent writes into its answer, and usable on its own with an `id` prop.
-->
<script lang="ts">
    import LabelSegment from "$lib/app/ai/LabelSegment.svelte";
    import {labelRepository} from "$lib/app/entities/EntityRepository.svelte";
    import {attributeOf} from "$lib/app/ai/toolCallAttributes";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import {_} from "svelte-i18n";

    let {
        id,
        attributes,
    }: {
        id?: string;
        /** Set when the markdown renderer instantiates this for an element of an answer. */
        attributes?: Record<string, string>;
    } = $props();

    const entityId = $derived(id ?? attributeOf(attributes, "id") ?? "");

    const entry = $derived(labelRepository.peek(entityId));

    // In an effect, not while rendering: asking starts a load and writes state.
    $effect(() => {
        if (entityId !== "") labelRepository.request(entityId);
    });
</script>

{#if entityId === ""}
    <!-- An element without an id is nothing this client can look up. -->
{:else if entry.value}
    <LabelSegment label={{id: entry.value.id, name: entry.value.name, color: entry.value.color}}/>
{:else if entry.isLoading}
    <Skeleton class="inline-block h-3.5 w-20 align-[-0.15em] rounded"/>
{:else}
    <LabelSegment label={{id: entityId, name: $_("ai.chat.messages.deletedReference"), color: "currentColor"}}/>
{/if}
