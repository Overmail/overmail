<script lang="ts">
    import {_} from "svelte-i18n";
    import * as Tabs from "$lib/components/ui/tabs";
    import {Skeleton} from "$lib/components/ui/skeleton";
    import EmailHtmlBody from "$lib/app/my-stack/EmailHtmlBody.svelte";
    import {useRepositories} from "$lib/repository/repositories";

    let {id}: {id: string} = $props();

    const {emailBody} = useRepositories();

    type MailBody = {text: string | null; html: string | null};

    let body = $state<MailBody | null>(null);
    let failed = $state(false);

    /** Which of the two is shown while the mail has both; see [shown] for what is drawn. */
    let mode: "html" | "text" = $state("html");

    // One request per mail, and the answer of the mail before it is dropped rather than shown:
    // stepping through a list is faster than a body comes back.
    $effect(() => {
        const current = id;

        body = null;
        failed = false;
        // The next mail is read the way a mail is meant to be read, whatever was picked here.
        mode = "html";

        let live = true;
        emailBody
            .getBody(current)
            .then((loaded) => {
                if (live) body = loaded;
            })
            .catch((error) => {
                console.error(error);
                if (live) failed = true;
            });

        return () => (live = false);
    });

    /** Empty is as good as absent: plenty of mails carry a part with nothing in it. */
    const present = (part: string | null | undefined) => (part?.trim() ? part : null);

    const html = $derived(present(body?.html));
    const text = $derived(present(body?.text));

    /** What is drawn: the picked one where there is a choice, and the one there is otherwise. */
    const shown = $derived(html === null ? "text" : text === null ? "html" : mode);
</script>

{#snippet htmlBody(source: string)}
    <EmailHtmlBody html={source}/>
{/snippet}

{#snippet textBody(source: string)}
    <!-- wrap-anywhere: a mail is written for whatever width its reader has, and a link without
         spaces in it must not push the panel wider. -->
    <div class="text-sm whitespace-pre-wrap wrap-anywhere">{source}</div>
{/snippet}

<div class="flex min-w-0 flex-col gap-3 px-6">
    {#if body === null && !failed}
        <!-- A body is one request away; the shape of a few lines says that it is coming. -->
        <Skeleton class="h-4 w-full"/>
        <Skeleton class="h-4 w-11/12"/>
        <Skeleton class="h-4 w-8/12"/>
    {:else if failed}
        <p class="text-sm text-muted-foreground">{$_("mails.content.failed")}</p>
    {:else if html !== null && text !== null}
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

            <Tabs.Content value="html" class="pt-3">{@render htmlBody(html)}</Tabs.Content>
            <Tabs.Content value="text" class="pt-3">{@render textBody(text)}</Tabs.Content>
        </Tabs.Root>
    {:else if html !== null}
        {@render htmlBody(html)}
    {:else if text !== null}
        {@render textBody(text)}
    {:else}
        <p class="text-sm text-muted-foreground">{$_("mails.content.empty")}</p>
    {/if}
</div>
