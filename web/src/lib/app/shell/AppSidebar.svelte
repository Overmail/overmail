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
    import {HouseIcon, StackIcon, TranslateIcon} from "phosphor-svelte";
    import {page} from "$app/state";
    import {_, locale} from "svelte-i18n";
    import {defaultLocale, localeNames, locales, setLocale, type Locale} from "$lib/i18n";

    // The label is a key rather than a string: the item has to re-render in the new language when
    // the locale changes, and a plain string captured here would not.
    const items = [
        {
            key: "app.nav.home",
            icon: HouseIcon,
            href: "/",
            isCurrent: () => page.url.pathname === "/"
        },
        {
            key: "app.nav.stack",
            icon: StackIcon,
            href: "/my-stack",
            isCurrent: () => page.url.pathname.startsWith("/my-stack"),
        },
    ];

    // `$locale` carries whatever was negotiated, e.g. `de-DE`; the menu only knows the base ones.
    const currentLocale = $derived(
        (locales as readonly string[]).includes($locale?.slice(0, 2) ?? "")
            ? ($locale!.slice(0, 2) as Locale)
            : defaultLocale
    );
</script>

<Sidebar>
    <SidebarHeader />
    <SidebarContent>
        <SidebarGroup>
            <SidebarGroupLabel>{$_('app.name')}</SidebarGroupLabel>
            <SidebarGroupContent>
                {#each items as item (item.href)}
                    {@const Icon = item.icon}
                    <SidebarMenuItem>
                        <SidebarMenuButton isActive={item.isCurrent()}>
                            {#snippet child({ props })}
                                <a href={item.href} {...props}>
                                    <Icon />
                                    <span>{$_(item.key)}</span>
                                </a>
                            {/snippet}
                        </SidebarMenuButton>
                    </SidebarMenuItem>
                {/each}
            </SidebarGroupContent>
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
