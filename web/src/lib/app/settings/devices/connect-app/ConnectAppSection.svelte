<script lang="ts">
    import {onMount} from "svelte";
    import {_} from "svelte-i18n";
    import {useRepositories} from "$lib/repository/repositories.ts";
    import {AuthCodeViewModel} from "./AuthCodeViewModel.svelte";
    import AuthQrCode from "./AuthQrCode.svelte";
    import CopyAuthCodeButton from "./CopyAuthCodeButton.svelte";

    const {deviceSettingsRepository} = useRepositories();
    const authCode = new AuthCodeViewModel(() => deviceSettingsRepository.fetchAuthCode());

    onMount(() => {
        void authCode.renew();
        return () => authCode.dispose();
    });
</script>

<section class="flex flex-col gap-1">
    <h2 class="text-xl">{$_("settings.devices.title")}</h2>
    <div class="flex flex-row flex-wrap gap-4 items-center p-4 border rounded-lg">
        <AuthQrCode state={authCode.state} onretry={() => void authCode.renew()} />
        <div class="flex flex-col gap-2">
            <span>{$_("settings.devices.scan")}</span>
            <a href="https://github.com/overmail/overmail/releases/latest" target="_blank" class="text-primary hover:underline">{$_("settings.devices.download")}</a>
            <CopyAuthCodeButton url={authCode.state.type === "ready" ? authCode.state.url : null} />
        </div>
    </div>
</section>
