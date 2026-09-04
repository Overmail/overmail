import {
	Virtualizer,
	elementScroll,
	observeElementOffset,
	observeElementRect,
	observeWindowOffset,
	observeWindowRect,
	windowScroll,
	type PartialKeys,
	type VirtualItem,
	type VirtualizerOptions
} from '@tanstack/svelte-virtual';

/** Everything TanStack Virtual needs beyond the DOM plumbing this hook fills in. */
export type VirtualizerRuneOptions<
	TScrollElement extends Element,
	TItemElement extends Element
> = PartialKeys<
	VirtualizerOptions<TScrollElement, TItemElement>,
	'observeElementRect' | 'observeElementOffset' | 'scrollToFn' | 'onChange'
>;

/** The same, for a list the page itself scrolls -- there is no element to be handed. */
export type WindowVirtualizerRuneOptions<TItemElement extends Element> = PartialKeys<
	VirtualizerOptions<Window, TItemElement>,
	'getScrollElement' | 'observeElementRect' | 'observeElementOffset' | 'scrollToFn' | 'onChange'
>;

/**
 * TanStack Virtual as a rune.
 *
 * The `@tanstack/svelte-virtual` adapter hands out a Svelte store whose options are captured when
 * the store is made -- before the scroll element exists and before the item count is known -- so
 * keeping it current means calling `setOptions` from something that also reads the store, which
 * feeds straight back into itself. [getOptions] is read fresh inside an effect instead, so
 * anything reactive it touches (the count, the scroll element) keeps the virtualizer up to date
 * without that loop.
 */
export function createVirtualizer<TScrollElement extends Element, TItemElement extends Element>(
	getOptions: () => VirtualizerRuneOptions<TScrollElement, TItemElement>
) {
	return virtualizerRune<TScrollElement, TItemElement>(() => ({
		observeElementRect,
		observeElementOffset,
		scrollToFn: elementScroll,
		...getOptions()
	}));
}

/**
 * The same, for a list the page scrolls rather than a box of its own.
 *
 * What a caller owes it beyond the options of the element version is `scrollMargin`: how far
 * down the page the list starts. Without it every item sits that far too high, and with it a
 * header above the list scrolls away before the rows begin to move.
 */
export function createWindowVirtualizer<TItemElement extends Element>(
	getOptions: () => WindowVirtualizerRuneOptions<TItemElement>
) {
	return virtualizerRune<Window, TItemElement>(() => ({
		// Null while there is no window: this is constructed on the server too, and the effects
		// that attach the observers are what does not run there.
		getScrollElement: () => (typeof window === 'undefined' ? null : window),
		observeElementRect: observeWindowRect,
		observeElementOffset: observeWindowOffset,
		scrollToFn: windowScroll,
		initialRect: { width: 0, height: 0 },
		...getOptions()
	}));
}

function virtualizerRune<TScrollElement extends Element | Window, TItemElement extends Element>(
	resolveOptions: () => Omit<VirtualizerOptions<TScrollElement, TItemElement>, 'onChange'>
) {
	// The virtualizer mutates itself in place, so it cannot be `$state` itself. What the getters
	// below hand out is copied off it whenever it reports a change.
	let items = $state<VirtualItem[]>([]);
	let totalSize = $state(0);

	function publish() {
		items = instance.getVirtualItems();
		totalSize = instance.getTotalSize();
	}

	const resolve = (): VirtualizerOptions<TScrollElement, TItemElement> => ({
		...resolveOptions(),
		onChange: () => publish()
	});

	const instance = new Virtualizer(resolve());

	$effect(() => {
		instance.setOptions(resolve());
		instance._willUpdate();
		// `onChange` only fires when the visible range moves. A count that grew below the fold
		// changes the total size and nothing else, and would otherwise never reach the getters.
		publish();
	});

	// Its own effect, and after the one above: mounting attaches the scroll and resize observers,
	// which needs the scroll element that effect has just handed over.
	$effect(() => instance._didMount());

	return {
		/** The items that need rendering right now, in list order. */
		get items() {
			return items;
		},
		/** How tall the full list is, which is what the scrollbar is sized from. */
		get totalSize() {
			return totalSize;
		},
		/** The instance itself, for `scrollToIndex` and friends. */
		get virtualizer() {
			return instance;
		}
	};
}
