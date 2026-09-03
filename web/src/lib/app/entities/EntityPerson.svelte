<!--
    A person by id, filled from the entity repository. Rendered for the `<person id="...">` element
    the agent writes into its answer, and usable on its own with an `id` prop.
-->
<script lang="ts">
    import SenderSegment from "$lib/app/ai/segments/SenderSegment.svelte";
    import {senderRepository} from "$lib/app/entities/EntityRepository.svelte";
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

    const entry = $derived(senderRepository.peek(entityId));

    // In an effect, not while rendering: asking starts a load and writes state.
    $effect(() => {
        if (entityId !== "") senderRepository.request(entityId);
    });
</script>

{#if entityId === ""}
    <!-- An element without an id is nothing this client can look up. -->
{:else if entry.value}
    <SenderSegment sender={{
        id: entry.value.id,
        name: entry.value.name,
        address: entry.value.address,
        avatarUrl: entry.value.avatarUrl,
        avatarPadding: entry.value.avatarPadding,
    }}/>
{:else if entry.isLoading}
    <Skeleton class="inline-block h-3.5 w-24 align-[-0.15em] rounded"/>
{:else}
    <SenderSegment sender={{
        id: entityId,
        name: null,
        address: $_("ai.chat.messages.deletedReference"),
        avatarUrl: null,
        avatarPadding: null,
    }}/>
{/if}
