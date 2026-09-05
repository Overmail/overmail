<script lang="ts">
    import {Button} from "$lib/components/ui/button";
    import { PlusIcon } from "phosphor-svelte";
    import NewEmailAccountDialog from "$lib/app/settings/email-accounts/new/NewEmailAccountDialog.svelte";
    import {_} from "svelte-i18n";

    let showNewEmailAccountDialog = $state(false);
</script>

<div class="flex flex-col">
    <Button
            class="w-fit"
            onclick={() => (showNewEmailAccountDialog = true)}
    >
        <PlusIcon />
        {$_("settings.emailAccounts.add")}
    </Button>
</div>

<NewEmailAccountDialog bind:open={showNewEmailAccountDialog} />