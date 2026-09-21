<script lang="ts">
    import * as AlertDialog from "$lib/components/ui/alert-dialog";
    import {_} from "svelte-i18n";
    import type {UserSession} from "$lib/repository/SessionsRepository";

    let {
        session = $bindable(null),
        signingOut,
        onconfirm,
    }: {
        /** The session of this browser, to be signed out; null closes the dialog. */
        session: UserSession | null;
        signingOut: boolean;
        /** Signs out. A success leaves the page; a failure closes the dialog. */
        onconfirm: (session: UserSession) => Promise<void>;
    } = $props();

    async function confirm() {
        const target = session;
        if (!target || signingOut) return;
        await onconfirm(target);
        session = null;
    }
</script>

<!-- Only for this browser's own session: signing that one out ends the page the user is on. -->
<AlertDialog.Root
        open={session !== null}
        onOpenChange={(open) => {
            if (!open && !signingOut) session = null;
        }}
>
    <AlertDialog.Content>
        <AlertDialog.Header>
            <AlertDialog.Title>{$_("settings.devices.sessions.signOut.title")}</AlertDialog.Title>
            <AlertDialog.Description>{$_("settings.devices.sessions.signOut.description")}</AlertDialog.Description>
        </AlertDialog.Header>

        <AlertDialog.Footer>
            <AlertDialog.Cancel disabled={signingOut}>
                {$_("settings.devices.sessions.signOut.cancel")}
            </AlertDialog.Cancel>
            <!-- `preventDefault` keeps the dialog open until the answer is in. -->
            <AlertDialog.Action
                    variant="destructive"
                    disabled={signingOut}
                    onclick={(event) => {
                        event.preventDefault();
                        void confirm();
                    }}
            >
                {$_("settings.devices.sessions.signOut.confirm")}
            </AlertDialog.Action>
        </AlertDialog.Footer>
    </AlertDialog.Content>
</AlertDialog.Root>
