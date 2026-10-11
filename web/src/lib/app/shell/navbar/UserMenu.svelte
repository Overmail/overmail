<!--
    The foot of the sidebar: who is signed in, and behind that what belongs to them rather than
    to a page -- the language, the settings, and signing out.

    One menu instead of a row each: none of the three is used often enough to stand in the
    sidebar the whole time, and all of them are about the account.
-->
<script lang="ts">
    import {onMount} from "svelte";
    import {goto} from "$app/navigation";
    import {page} from "$app/state";
    import {_, locale} from "svelte-i18n";
    import {CaretUpDownIcon, GearIcon, SignOutIcon, TranslateIcon} from "phosphor-svelte";
    import * as Avatar from "$lib/components/ui/avatar";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarMenuSkeleton} from "$lib/components/ui/sidebar";
    import {defaultLocale, localeNames, locales, setLocale, type Locale} from "$lib/i18n";
    import type {CurrentUser} from "$lib/repository/CurrentUserRepository";
    import {useRepositories} from "$lib/repository/repositories";
    import {initials} from "$lib/utils";

    const {currentUser} = useRepositories();

    let user = $state<CurrentUser | null>(null);

    onMount(() => {
        currentUser.get().then((result) => (user = result));
    });

    const name = $derived(user === null ? "" : `${user.firstname} ${user.lastname}`.trim());

    // `$locale` carries whatever was negotiated, e.g. `de-DE`; the menu only knows the base ones.
    const currentLocale = $derived(
        (locales as readonly string[]).includes($locale?.slice(0, 2) ?? "")
            ? ($locale!.slice(0, 2) as Locale)
            : defaultLocale
    );

    /** The settings are a dialog over whatever page is open, and the url is what opens it. */
    function openSettings() {
        const url = new URL(page.url);
        url.searchParams.set("settings", "");
        goto(url, {replaceState: true, noScroll: true});
    }

    /** A full page load: the server ends the session and redirects to the sign-in. */
    function logout() {
        window.location.href = "/api/webapp/auth/logout";
    }
</script>

<SidebarMenu>
    <SidebarMenuItem>
        {#if user === null}
            <!-- The row is there before the name is: the menu has nothing to say without it. -->
            <SidebarMenuSkeleton showIcon/>
        {:else}
            <DropdownMenu.Root>
                <DropdownMenu.Trigger>
                    {#snippet child({props})}
                        <!-- Collapsed, the row is the avatar: less padding than a row with an icon
                             has, because the avatar is wider than one. -->
                        <SidebarMenuButton {...props} class="group-data-[collapsible=icon]:p-1!">
                            <Avatar.Root size="sm" class="-ms-0.5 shrink-0 transition-[margin] group-data-[collapsible=icon]:ms-0">
                                <Avatar.Fallback>{initials(name || user!.address)}</Avatar.Fallback>
                            </Avatar.Root>
                            <span class="flex-1">{name || user!.address}</span>
                            <CaretUpDownIcon class="text-sidebar-foreground/50"/>
                        </SidebarMenuButton>
                    {/snippet}
                </DropdownMenu.Trigger>

                <!-- As wide as the row it opens from, so it reads as that row unfolding. -->
                <DropdownMenu.Content side="top" align="start" class="w-(--bits-dropdown-menu-anchor-width) min-w-52">
                    <DropdownMenu.Sub>
                        <DropdownMenu.SubTrigger>
                            <TranslateIcon/>
                            <span class="flex-1">{$_("app.language.label")}</span>
                            <span class="text-muted-foreground text-xs">{localeNames[currentLocale]}</span>
                        </DropdownMenu.SubTrigger>
                        <DropdownMenu.SubContent class="w-40">
                            <DropdownMenu.RadioGroup
                                    value={currentLocale}
                                    onValueChange={(value) => setLocale(value as Locale)}
                            >
                                {#each locales as option (option)}
                                    <DropdownMenu.RadioItem value={option}>
                                        {localeNames[option]}
                                    </DropdownMenu.RadioItem>
                                {/each}
                            </DropdownMenu.RadioGroup>
                        </DropdownMenu.SubContent>
                    </DropdownMenu.Sub>

                    <DropdownMenu.Item onclick={openSettings}>
                        <GearIcon/>
                        {$_("settings.title")}
                    </DropdownMenu.Item>

                    <DropdownMenu.Separator/>

                    <DropdownMenu.Item variant="destructive" onclick={logout}>
                        <SignOutIcon/>
                        {$_("app.user.logout")}
                    </DropdownMenu.Item>
                </DropdownMenu.Content>
            </DropdownMenu.Root>
        {/if}
    </SidebarMenuItem>
</SidebarMenu>
