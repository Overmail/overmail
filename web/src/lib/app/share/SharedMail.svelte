<!--
    A shared mail as somebody without an account here reads it: what it is about, who wrote it,
    when, and -- where the share was made with them -- how the owner sorted it.

    Nothing about it can be changed from here, so this shares only the body renderer with the app's
    own view: the rest of that one is buttons and label pickers a visitor has no business having.
-->
<script lang="ts">
    import {Badge} from "$lib/components/ui/badge";
    import {Separator} from "$lib/components/ui/separator";
    import MailBody from "$lib/app/mails/detail_panel/MailBody.svelte";
    import type {SharedEmail} from "$lib/repository/SharedEmailRepository";
    import {_, locale} from "svelte-i18n";

    let {shared}: {shared: SharedEmail} = $props();

    const metadata = $derived(shared.metadata);

    /** The sender as they signed the mail; the bare address where there is no name. */
    const sender = $derived(
        metadata === null
            ? ""
            : metadata.senderName
              ? `${metadata.senderName} <${metadata.senderAddress}>`
              : metadata.senderAddress
    );

    /** The full moment, not "5 minutes ago": a shared mail is usually read long after it arrived. */
    const sentAt = $derived(
        metadata === null
            ? null
            : new Date(metadata.sent * 1000).toLocaleString($locale ?? undefined, {
                  dateStyle: "long",
                  timeStyle: "short",
              })
    );
</script>

<div class="flex min-w-0 flex-col gap-5">
    {#if metadata}
        <div class="flex min-w-0 flex-col gap-2">
            <h1 class="text-xl font-medium text-balance wrap-anywhere">
                {metadata.subject || $_("mails.noSubject")}
            </h1>

            <div class="text-muted-foreground flex flex-row flex-wrap items-center gap-x-2 gap-y-0.5 text-sm">
                <span class="wrap-anywhere">{$_("share.sender", {values: {sender}})}</span>
                {#if sentAt}
                    <span aria-hidden="true">&middot;</span>
                    <time datetime={new Date(metadata.sent * 1000).toISOString()}>{sentAt}</time>
                {/if}
            </div>

            {#if metadata.labels.length > 0}
                <div class="flex flex-row flex-wrap items-center gap-1 pt-1">
                    {#each metadata.labels as label (label.name)}
                        <Badge variant="secondary" class="shrink-0 font-normal" color={label.color}>
                            {label.name}
                        </Badge>
                    {/each}
                </div>
            {/if}
        </div>

        <!-- Between who wrote it and what they wrote: the head of the mail is a different kind of
             thing to the mail, and the rule is what says so without a second surface. -->
        <Separator />
    {/if}

    {#if shared.content}
        <MailBody text={shared.content.text} html={shared.content.html} />
    {/if}
</div>
