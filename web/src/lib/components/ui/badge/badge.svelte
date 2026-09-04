<script lang="ts" module>
	import { type VariantProps, tv } from "tailwind-variants";

	export const badgeVariants = tv({
		base: "h-5.5 gap-1 rounded-xs px-1.5 text-sm font-medium transition-all has-data-[icon=inline-end]:pr-1 has-data-[icon=inline-start]:pl-1 [&>svg]:size-3.5! group/badge inline-flex w-fit shrink-0 items-center justify-center overflow-hidden whitespace-nowrap transition-colors focus-visible:ring-[3px] focus-visible:ring-ring/50 aria-invalid:ring-destructive/20 dark:aria-invalid:ring-destructive/40 [&>svg]:pointer-events-none",
		variants: {
			variant: {
				default: "bg-primary text-primary-foreground [a]:hover:bg-primary/80",
				secondary: "bg-secondary text-secondary-foreground [a]:hover:bg-secondary/80",
				destructive: "bg-destructive/10 text-destructive focus-visible:ring-destructive/20 dark:bg-destructive/20 dark:focus-visible:ring-destructive/40 [a]:hover:bg-destructive/20",
				outline: "border border-border text-foreground [a]:hover:bg-muted [a]:hover:text-muted-foreground",
				ghost: "hover:bg-muted hover:text-muted-foreground dark:hover:bg-muted/50",
				link: "text-primary underline-offset-4 hover:underline",
			},
		},
		defaultVariants: {
			variant: "default",
		},
	});

	export type BadgeVariant = VariantProps<typeof badgeVariants>["variant"];
</script>

<script lang="ts">
	import { cn, type WithElementRef } from "$lib/utils.js";
	import type { HTMLAnchorAttributes } from "svelte/elements";

	let {
		ref = $bindable(null),
		href,
		class: className,
		variant = "default",
		color,
		style,
		children,
		...restProps
	}: WithElementRef<HTMLAnchorAttributes> & {
		variant?: BadgeVariant;
		/**
		 * An arbitrary colour (any CSS colour) for this badge. It only ever reaches the
		 * background; the text stays the standard foreground, so the contrast holds no matter
		 * which colour comes in. See the style block below.
		 */
		color?: string;
	} = $props();

	const tintStyle = $derived(
		[color && `--badge-color: ${color}`, style].filter(Boolean).join("; ") || undefined,
	);
</script>

<svelte:element
	this={href ? "a" : "span"}
	bind:this={ref}
	data-slot="badge"
	data-tinted={color ? "" : undefined}
	{href}
	class={cn(badgeVariants({ variant }), className)}
	style={tintStyle}
	{...restProps}
>
	{@render children?.()}
</svelte:element>

<!--
	A tinted badge keeps the caller's hue but not its lightness: `oklch(from …)` throws away the
	source L and C and rebuilds the fill at a lightness this theme is known to work against, so a
	colour picked from a label name — which knows nothing about the theme — cannot end up as an
	unreadable fill. Chroma is capped rather than taken as-is, both to stay near the display gamut
	(where out-of-range chroma gets mapped away anyway) and to keep the chip a surface instead of a
	shout. Unlayered component CSS outranks Tailwind's `@layer utilities`, so this wins over the
	variant's own `bg-*`/`text-*` without depending on class order. The fill is the whole edge:
	there is no border to carry the hue as well.
-->
<style>
	[data-slot="badge"][data-tinted] {
		background-color: oklch(from var(--badge-color) 0.93 min(c, 0.09) h);
		color: var(--foreground);
	}

	a[data-slot="badge"][data-tinted]:hover {
		background-color: oklch(from var(--badge-color) 0.88 min(c, 0.11) h);
	}

	:global(.dark) [data-slot="badge"][data-tinted] {
		background-color: oklch(from var(--badge-color) 0.28 min(c, 0.09) h);
	}

	:global(.dark) a[data-slot="badge"][data-tinted]:hover {
		background-color: oklch(from var(--badge-color) 0.34 min(c, 0.11) h);
	}
</style>
