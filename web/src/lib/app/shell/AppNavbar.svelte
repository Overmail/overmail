<script lang="ts">
    import {
        Sidebar,
        SidebarContent,
        SidebarFooter,
        SidebarGroup,
        SidebarGroupContent,
        SidebarGroupLabel,
        SidebarHeader,
        SidebarMenu,
        SidebarMenuButton,
        SidebarMenuItem
    } from "$lib/components/ui/sidebar";
    import * as DropdownMenu from "$lib/components/ui/dropdown-menu";
    import {TranslateIcon, UserCircleIcon} from "phosphor-svelte";
    import {navItems} from "$lib/app/shell/nav";
    import {page} from "$app/state";
    import {onMount} from "svelte";
    import {_, locale} from "svelte-i18n";
    import {defaultLocale, localeNames, locales, setLocale, type Locale} from "$lib/i18n";
    import ViewList from "$lib/app/shell/navbar/views/ViewList.svelte";
    import favicon from "$lib/assets/favicon.svg";
    import {useRepositories} from "$lib/repository/repositories";
    import type {Inbox} from "$lib/repository/InboxRepository";
    import {accountViewId} from "$lib/app/views/predefinedViews";
    import {openViewId, viewHref} from "$lib/app/views/viewPath";

    const {inboxes} = useRepositories();

    let accounts = $state<Inbox[]>([]);

    // Read once, in the browser: the list needs the session, and the sidebar is there for as long
    // as the app is. An account connected since shows up with the next load.
    onMount(() => {
        inboxes.list().then((list) => (accounts = list)).catch((error) => console.error(error));
    });

    // `$locale` carries whatever was negotiated, e.g. `de-DE`; the menu only knows the base ones.
    const currentLocale = $derived(
        (locales as readonly string[]).includes($locale?.slice(0, 2) ?? "")
            ? ($locale!.slice(0, 2) as Locale)
            : defaultLocale
    );
</script>

<Sidebar compact>
    <SidebarHeader>
        <!-- px-2.5 is the rows' own padding, so the logo stands in the column of their icons. -->
        <div class="flex h-9 items-center gap-2 px-2.5 mt-3 pb-2">
            <img src={favicon} alt="" class="size-8" />
            <span class="font-display text-lg leading-none">{$_('app.name')}</span>
        </div>
    </SidebarHeader>
    <SidebarContent>
        <SidebarGroup>
            <SidebarGroupContent>
                <SidebarMenu>
                    {#each navItems as item (item.href)}
                        {@const Icon = item.icon}
                        <SidebarMenuItem>
                            <SidebarMenuButton isActive={item.matches(page.url)}>
                                {#snippet child({ props })}
                                    <a href={item.href} {...props}>
                                        <Icon />
                                        <span>{$_(item.key)}</span>
                                    </a>
                                {/snippet}
                            </SidebarMenuButton>
                        </SidebarMenuItem>
                    {/each}
                </SidebarMenu>
            </SidebarGroupContent>
        </SidebarGroup>

        <!-- One row per connected mail account, each a view the app brings: the account as a
             whole, see `accountView`. Nothing while there is none -- a heading over an empty
             list says less than no heading. -->
        {#if accounts.length > 0}
            <SidebarGroup>
                <SidebarGroupLabel>{$_('app.nav.accounts')}</SidebarGroupLabel>
                <SidebarGroupContent>
                    <SidebarMenu>
                        {#each accounts as account (account.id)}
                            {@const viewId = accountViewId(account.id)}
                            <SidebarMenuItem>
                                <SidebarMenuButton isActive={page.url.pathname === "/" && openViewId(page.url) === viewId}>
                                    {#snippet child({ props })}
                                        <a href={viewHref(viewId, account.username, page.url)} {...props}>
                                            <UserCircleIcon />
                                            <span>{account.username}</span>
                                        </a>
                                    {/snippet}
                                </SidebarMenuButton>
                            </SidebarMenuItem>
                        {/each}
                    </SidebarMenu>
                </SidebarGroupContent>
            </SidebarGroup>
        {/if}

        <SidebarGroup>
            <SidebarGroupLabel>Ansichten</SidebarGroupLabel>
            <ViewList />
        </SidebarGroup>
    </SidebarContent>

    <SidebarFooter>
        <SidebarMenu>
            <SidebarMenuItem>
                <DropdownMenu.Root>
                    <DropdownMenu.Trigger>
                        {#snippet child({ props })}
                            <SidebarMenuButton {...props}>
                                <TranslateIcon />
                                <span>{localeNames[currentLocale]}</span>
                            </SidebarMenuButton>
                        {/snippet}
                    </DropdownMenu.Trigger>

                    <DropdownMenu.Content side="top" align="start" class="w-48">
                        <DropdownMenu.Label>{$_('app.language.label')}</DropdownMenu.Label>
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
                    </DropdownMenu.Content>
                </DropdownMenu.Root>
            </SidebarMenuItem>
        </SidebarMenu>
    </SidebarFooter>
</Sidebar>
