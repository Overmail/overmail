<script lang="ts">
    import * as Breadcrumb from "$lib/components/ui/breadcrumb";
    import {SETUP_STEPS, type SetupStep} from "./NewEmailAccountViewModel.svelte.ts";
    import {_} from "svelte-i18n";

    let {
        current,
        canEnter,
        onNavigate,
    }: {
        current: SetupStep,
        /** Whether a step is open yet -- an unchecked step ahead is not something to jump to. */
        canEnter: (step: SetupStep) => boolean,
        onNavigate: (step: SetupStep) => void,
    } = $props();
</script>

<!--
  What is behind and what is still to come, which is the thing a three-step form has to say up
  front. A step already checked out is a link back to it; one that is not open yet is plain text,
  because there is nothing to show there until the step before it answers.
-->
<Breadcrumb.Root>
    <Breadcrumb.List>
        {#each SETUP_STEPS as step, index (step)}
            {#if index > 0}
                <Breadcrumb.Separator />
            {/if}
            <Breadcrumb.Item>
                {#if step === current}
                    <Breadcrumb.Page>{$_(`settings.emailAccounts.new.steps.${step}`)}</Breadcrumb.Page>
                {:else if canEnter(step)}
                    <!--
                      A button, not a `Breadcrumb.Link`: that one is an anchor, and this navigates
                      inside a dialog rather than to a url. Its one class is what keeps the two
                      looking alike.
                    -->
                    <button
                            type="button"
                            class="hover:text-foreground transition-colors"
                            onclick={() => onNavigate(step)}
                    >
                        {$_(`settings.emailAccounts.new.steps.${step}`)}
                    </button>
                {:else}
                    <span class="text-muted-foreground/60">
                        {$_(`settings.emailAccounts.new.steps.${step}`)}
                    </span>
                {/if}
            </Breadcrumb.Item>
        {/each}
    </Breadcrumb.List>
</Breadcrumb.Root>
