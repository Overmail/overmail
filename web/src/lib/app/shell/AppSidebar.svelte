<script lang="ts">
    import {
        Sidebar,
        SidebarContent,
        SidebarFooter,
        SidebarGroup,
        SidebarGroupContent,
        SidebarGroupLabel,
        SidebarHeader,
        SidebarMenuButton,
        SidebarMenuItem
    } from "$lib/components/ui/sidebar";
    import {HouseIcon, StackIcon} from "phosphor-svelte";
    import {page} from "$app/state";

    const items = [
        {
            label: "Home",
            icon: HouseIcon,
            href: "/",
            isCurrent: () => page.url.pathname === "/"
        },
        {
            label: "Stack",
            icon: StackIcon,
            href: "/my-stack",
            isCurrent: () => page.url.pathname.startsWith("/my-stack"),
        },
    ];
</script>

<Sidebar>
    <SidebarHeader />
    <SidebarContent>
        <SidebarGroup>
            <SidebarGroupLabel>Overmail</SidebarGroupLabel>
            <SidebarGroupContent>
                {#each items as item (item.href)}
                    {@const Icon = item.icon}
                    <SidebarMenuItem>
                        <SidebarMenuButton isActive={item.isCurrent()}>
                            {#snippet child({ props })}
                                <a href={item.href} {...props}>
                                    <Icon />
                                    <span>{item.label}</span>
                                </a>
                            {/snippet}
                        </SidebarMenuButton>
                    </SidebarMenuItem>
                {/each}
            </SidebarGroupContent>
        </SidebarGroup>
    </SidebarContent>

    <SidebarFooter>

    </SidebarFooter>
</Sidebar>