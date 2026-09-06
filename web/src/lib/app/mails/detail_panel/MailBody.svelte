<!--
    What a mail says, once both halves of it are here.

    Only the drawing: which of the two parts a mail brought, which one is shown where it brought
    both, and what an empty one looks like. Where the parts come from is the caller's business --
    the panel reads them through the api (see Content), the share page gets them with the link.
-->
<script lang="ts">
    import {_} from "svelte-i18n";
    import * as Tabs from "$lib/components/ui/tabs";
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";

    let {
        text,
        html,
    }: {
        text: string | null,
        html: string | null,
    } = $props();

    /** Which of the two is shown while the mail has both; see [shown] for what is drawn. */
    let mode: "html" | "text" = $state("html");

    /** Empty is as good as absent: plenty of mails carry a part with nothing in it. */
    const present = (part: string | null | undefined) => (part?.trim() ? part : null);

    const htmlPart = $derived(present(html));
    const textPart = $derived(present(text));

    /** What is drawn: the picked one where there is a choice, and the one there is otherwise. */
    const shown = $derived(htmlPart === null ? "text" : textPart === null ? "html" : mode);
</script>

{#snippet htmlBody(source: string)}
    <EmailHtmlBody html={source}/>
{/snippet}

{#snippet textBody(source: string)}
    <!-- wrap-anywhere: a mail is written for whatever width its reader has, and a link without
         spaces in it must not push the panel wider. -->
    <div class="text-sm whitespace-pre-wrap wrap-anywhere">{source}</div>
{/snippet}

{#if htmlPart !== null && textPart !== null}
    <!-- Tabs rather than a toggle: the two are views of the same mail, and this way the body
         below is the tab's panel. -->
    <Tabs.Root
            value={shown}
            onValueChange={(value) => (mode = value === "text" ? "text" : "html")}
            class="flex min-w-0 flex-col"
    >
        <Tabs.List>
            <Tabs.Trigger value="html">{$_("mails.content.html")}</Tabs.Trigger>
            <Tabs.Trigger value="text">{$_("mails.content.text")}</Tabs.Trigger>
        </Tabs.List>

        <Tabs.Content value="html" class="pt-3">{@render htmlBody(htmlPart)}</Tabs.Content>
        <Tabs.Content value="text" class="pt-3">{@render textBody(textPart)}</Tabs.Content>
    </Tabs.Root>
{:else if htmlPart !== null}
    {@render htmlBody(htmlPart)}
{:else if textPart !== null}
    {@render textBody(textPart)}
{:else}
    <p class="text-sm text-muted-foreground">{$_("mails.content.empty")}</p>
{/if}
