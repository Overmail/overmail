<script lang="ts" module>
	/** One card, as it is handed to the tooltip snippet. */
	export type UsageGraphDay = {
		/** `yyyy-mm-dd`, the same key the state is filled with. */
		date: string;
		count: number;
	};

	/**
	 * Either nothing has been counted yet, or here is what was counted.
	 *
	 * A day nothing happened on is absent from [days] rather than held at zero — the graph draws a
	 * card for every day of the year either way, so an entry would only be a second way of saying
	 * the same thing.
	 */
	export type UsageGraphState =
		| { type: 'loading' }
		| { type: 'data'; days: ReadonlyMap<string, number> };
</script>

<script lang="ts">
	import { untrack, type Snippet } from 'svelte';
	import { locale } from 'svelte-i18n';
	import * as Tooltip from '$lib/components/ui/tooltip';

	// `state` is renamed on the way in: a local binding under that name would turn every `$state`
	// in this file into a store subscription.
	let {
		year,
		state: usage,
		color = 'var(--primary)',
		label = `Usage per day in ${year}`,
		tooltip
	}: {
		year: number;
		state: UsageGraphState;
		/**
		 * What a busy day is drawn in, as any CSS colour. It is mixed into the empty card rather
		 * than used as a fill, so a colour that knows nothing about the theme still reads in both.
		 */
		color?: string;
		/**
		 * What the grid is, for a reader who is not looking at it. The graph itself does not know
		 * what it counted — a tag's mails, a whole mailbox — so the caller says.
		 */
		label?: string;
		/**
		 * What stands in the bubble over the card the pointer is on. Only the caller knows what was
		 * counted, so only the caller can word it; the bubble itself is the tooltip's, so this is
		 * its contents and not a box of its own. Without it the graph ignores the pointer entirely.
		 */
		tooltip?: Snippet<[UsageGraphDay]>;
	} = $props();

	const ROWS = 7;

	/**
	 * The rail down the left, row for row with the cards — Monday first, like the rows themselves.
	 *
	 * Every other day is named and the rest are blank: seven labels down 16px rows read as a wall of
	 * text, and four are enough to find a row by counting one step from the nearest.
	 *
	 * Named by Intl rather than from the catalogues: a weekday is what a locale already knows, and
	 * the graph follows the language the app is set to rather than the one the browser is in.
	 */
	function weekdayLabels(forLocale: string | null | undefined): string[] {
		const format = new Intl.DateTimeFormat(forLocale ?? undefined, { weekday: 'short' });
		// 1 January 2024 was a Monday, which is the row this rail starts on.
		return Array.from({ length: ROWS }, (_, row) =>
			row % 2 === 0 ? format.format(new Date(2024, 0, 1 + row)) : ''
		);
	}

	/** How far apart two neighbouring cards are in the wave; the whole grid takes ~1.5s to cross. */
	const WAVE_STEP_MS = 25;

	/**
	 * What the quietest day that happened at all is worth.
	 *
	 * Without a floor a single mail against a busy day comes out as a percent or two of tint, which
	 * is a card that says "nothing here" while something did happen.
	 */
	const MIN_SHARE = 0.2;

	const isLeapYear = (of: number) => new Date(of, 1, 29).getDate() === 29;

	/** Built by hand rather than through `toISOString`, which would answer in UTC and shift a day. */
	const isoDate = (date: Date) =>
		`${date.getFullYear()}-${`${date.getMonth() + 1}`.padStart(2, '0')}-${`${date.getDate()}`.padStart(2, '0')}`;

	/**
	 * How many slots stand in front of 1 January, so that every row is one weekday.
	 *
	 * Monday first, as the week is written here — `getDay()` counts from Sunday.
	 */
	const offsetOf = (of: number) => (new Date(of, 0, 1).getDay() + 6) % 7;

	/**
	 * One place in the grid.
	 *
	 * Slots are the fixed thing here: slot 0 is the card in the top left corner, and they run down
	 * a column before starting the next — the same places, whatever year is on screen.
	 */
	type Slot = {
		/** The day this slot stands for, or null for a slot the year does not reach. */
		date: string | null;
		count: number;
		/** A day that has not happened yet, and therefore has nothing to say rather than nothing. */
		ahead: boolean;
		row: number;
		column: number;
		/** What the card is worth, as a percentage of [color]. */
		share: number;
	};

	/** A year as it is drawn: how many weeks wide it comes out, and what stands in every slot. */
	type Drawing = { columns: number; slots: Slot[] };

	/**
	 * [of] against [days], slot by slot — the whole translation between a date and where it is
	 * drawn, and the only place the year's offset is applied.
	 *
	 * A year opens on a different weekday each time — 2026 on a Thursday, 2025 on a Wednesday — and
	 * that moves which day lands in which slot. It does not move the slots. So a year that is
	 * switched leaves every card standing where it is and changes what it is worth, which is a
	 * colour the card transitions into; nothing is laid out anew, so there is nothing to jump. The
	 * slots the year does not reach, before 1 January and after 31 December, stand empty.
	 *
	 * A day is measured against the busiest day of its own year, so the graph is read within itself
	 * rather than against a scale nobody stated. Without counts every card comes out empty, which
	 * is the flat grid the first load shimmers over.
	 */
	function draw(of: number, days: ReadonlyMap<string, number> | null): Drawing {
		const offset = offsetOf(of);
		const total = isLeapYear(of) ? 366 : 365;
		const columns = Math.ceil((offset + total) / ROWS);
		const busiest = days?.size ? Math.max(...days.values()) : 0;
		// Read as the drawing is made rather than kept ticking: a year is redrawn often enough,
		// and a graph that is open at midnight showing yesterday's edge is nobody's problem.
		// `yyyy-mm-dd` sorts the way the calendar does, so a plain comparison is the whole test.
		const today = isoDate(new Date());

		return {
			columns,
			slots: Array.from({ length: columns * ROWS }, (_, slot): Slot => {
				const day = slot - offset;
				const date = day >= 0 && day < total ? isoDate(new Date(of, 0, 1 + day)) : null;
				const count = (date === null ? 0 : days?.get(date)) ?? 0;

				return {
					date,
					count,
					ahead: date !== null && date > today,
					row: slot % ROWS,
					column: Math.floor(slot / ROWS),
					share:
						count === 0 || busiest === 0
							? 0
							: Math.round((MIN_SHARE + (1 - MIN_SHARE) * (count / busiest)) * 100)
				};
			})
		};
	}

	const isLoading = $derived(usage.type === 'loading');
	const counts = $derived(usage.type === 'data' ? usage.days : null);

	/**
	 * The last drawing that had its counts in. Kept while the next ones are on their way: the year
	 * on the heading has already changed, and the grid holds what it had under the shimmer instead
	 * of emptying itself and filling up again.
	 */
	let drawn = $state<Drawing | null>(null);

	// Counts are what this waits for, and the year is only read along with them, never tracked:
	// the two arrive as separate props and the year is set first, so a run on the year alone would
	// draw exactly the mismatch this is here to avoid -- the new year's weekdays under the old
	// year's numbers.
	$effect(() => {
		if (counts !== null) drawn = draw(untrack(() => year), counts);
	});

	/** What is on screen: the drawing that has its counts, or an empty year until the first lands. */
	const shown = $derived(drawn ?? draw(year, null));

	// The slot the pointer is on, and the card standing in it. The card is kept rather than
	// measured: the bubble is hung off the element itself and places itself against it.
	let hoveredSlot = $state<number | null>(null);
	let hoveredCard = $state<HTMLElement | null>(null);
	const hovered = $derived(hoveredSlot === null ? null : (shown.slots[hoveredSlot] ?? null));

	/**
	 * Over a slot that is a day, and only while the counts on screen are the ones that were asked
	 * for: the cards hold the last drawing over when a year is switched, and a number read off that
	 * would be last year's against a day of this one.
	 */
	const hasBubble = $derived(tooltip !== undefined && hovered?.date != null && !isLoading);

	// Nothing triggers this tooltip -- the pointer on a card does, and the tooltip is told. Bound
	// rather than handed down, so it can still be closed out from under us.
	let isOpen = $state(false);

	$effect(() => {
		isOpen = hasBubble;
	});

	const weekdays = $derived(weekdayLabels($locale));

	function leave() {
		hoveredSlot = null;
		hoveredCard = null;
	}
</script>

<div class="flex w-fit gap-1">
	<!--
		Its own grid beside the cards rather than a first column inside them, so the cards keep their
		own box: a rail of unknown width in front of them would push every one of them along by
		however wide the longest name happens to render.

		Same seven rows and the same gap, so the two line up without either knowing the other's size.
	-->
	<div class="grid grid-rows-7 gap-1 pr-0.5" aria-hidden="true">
		{#each weekdays as weekday, row (row)}
			<div class="text-muted-foreground flex h-3 items-center text-[10px] leading-none">
				{weekday}
			</div>
		{/each}
	</div>

	<Tooltip.Provider delayDuration={0}>
		<Tooltip.Root bind:open={isOpen} disableHoverableContent>
			<!--
				One picture rather than 365 of them: a reader is told what the graph is, and the cards
				below it are the drawing. The colour is stated once here too, so the caller's string
				reaches exactly one place and every card only carries its own share of it.

				Keyed by slot, the one thing that does not move: a card is where it is and what it
				stands for is looked up, so a year that is switched updates the cards that are already
				there instead of tearing the grid down and putting a new one up.
			-->
			<div
				class="grid w-fit grid-flow-col grid-rows-7 gap-1"
				style="--usage-tint: {color}"
				role="img"
				aria-label={label}
				onmouseleave={leave}
			>
				{#each shown.slots as slot, index (index)}
					<div
						class="card enter size-3 overflow-hidden rounded-xs"
						class:blank={slot.date === null}
						class:ahead={slot.ahead}
						class:shimmer={isLoading}
						style="--wave-delay: {(slot.row + slot.column) *
							WAVE_STEP_MS}ms; --tint-share: {slot.share}%"
						aria-hidden="true"
						onmouseenter={(event) => {
							hoveredSlot = index;
							hoveredCard = event.currentTarget;
						}}
					></div>
				{/each}
			</div>

			{#if tooltip && hovered?.date}
				<!--
					Hung off the card the pointer is on rather than placed by hand off the grid's
					geometry: the bubble is portalled out of the graph, so it is neither clipped by
					the box the graph scrolls in nor left to argue about stacking with anything
					around it.
				-->
				<Tooltip.Content customAnchor={hoveredCard} sideOffset={6}>
					{@render tooltip({ date: hovered.date, count: hovered.count })}
				</Tooltip.Content>
			{/if}
		</Tooltip.Root>
	</Tooltip.Provider>
</div>

<style>
	/*
		Only the colour travels. It runs along the same diagonal the shimmer does, at half its step
		so the counts land in about half the time the wave takes to cross.
	*/
	.card {
		/* The shimmer overlay is placed against the card, so the card is what it is placed in. */
		position: relative;

		/*
			Mixed into the empty card rather than laid over it: the tint is whatever the caller
			handed over and knows nothing about the theme, so a flat fill would be unreadable in one
			of them. A share of 0% is the empty card exactly, which is what a quiet day, a slot
			outside the year and the whole loading state all are.
		*/
		background-color: color-mix(
			in oklab,
			var(--usage-tint, var(--primary)) var(--tint-share, 0%),
			var(--accent)
		);
		transition: background-color 500ms ease-out;
		transition-delay: calc(var(--wave-delay) / 2);
	}

	/*
		A slot the year does not reach — the days of the week before 1 January, the tail after 31
		December. Drawn as nothing rather than left out, so that a year which opens on another
		weekday moves the gap by fading one card in and another out, with no card appearing in a
		place where none stood.
	*/
	.blank {
		background-color: transparent;
	}

	/* Nothing is being counted there, so there is nothing to say it is being counted. */
	.blank::after {
		animation: none;
	}

	/*
		A day that has not happened yet. The same card, mixed most of the way into the page: an
		empty day and a day that could not have had anything on it should not read alike, and the
		year ahead of today is the larger part of the grid for most of a year.

		Faded through the colour rather than through `opacity`, which the card is already spending
		on its way in — a card that entered at full strength and then dropped to a third of it once
		the animation let go would be a blink of its own.
	*/
	.ahead {
		background-color: color-mix(
			in oklab,
			color-mix(in oklab, var(--usage-tint, var(--primary)) var(--tint-share, 0%), var(--accent))
				30%,
			transparent
		);
	}

	/*
		The grid itself arrives in one piece, undelayed: what is being waited for is the counts, and
		staggering the empty cards as well made the year look like it was being built rather than
		filled in. So every card is put down at once, and the colour then walks across them.

		Sits on the card from the start rather than going on when the counts land: an animation
		replays every time its class is put back on, so counts arriving -- or a year being switched
		-- would fade all 365 cards out and in again over a grid that is already on screen. Now it
		plays once as the grid is put down, and everything after that is colour.
	*/
	.enter {
		animation: card-enter 320ms ease-out;
	}

	@keyframes card-enter {
		from {
			opacity: 0;
			transform: scale(0.4);
		}
		to {
			opacity: 1;
			transform: scale(1);
		}
	}

	/*
		The light passes over the cards themselves rather than as a sheet across the grid: a band
		drawn over the top would light the gaps too, and the shape being waited for is the cards.
		Each one carries its own delay, so the wave travels down and to the right.

		The overlay sits on every card and only the animation comes and goes. Taking the whole
		thing away with the class dropped it wherever the wave happened to stand, and a card that
		is a tenth ink one frame and none the next is a blink -- times 365, right at the moment the
		counts land. Without the animation it holds at nothing, and whatever it was showing when
		the counts arrived fades out from there.
	*/
	.card::after {
		content: '';
		position: absolute;
		inset: 0;
		/* The theme's ink, so this darkens on a light card and lightens on a dark one. */
		background-color: var(--foreground);
		opacity: 0;
		transition: opacity 400ms ease-out;
	}

	.shimmer::after {
		animation: card-shimmer 2.4s ease-in-out infinite;
		animation-delay: var(--wave-delay);
	}

	/* Barely there on purpose: it says "still counting", it is not the thing being looked at. */
	@keyframes card-shimmer {
		0%,
		100% {
			opacity: 0;
		}
		50% {
			opacity: 0.09;
		}
	}

	@media (prefers-reduced-motion: reduce) {
		.card,
		.card::after {
			transition: none;
		}

		.enter,
		.shimmer::after {
			animation: none;
		}
	}
</style>
