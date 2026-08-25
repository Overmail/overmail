import * as Blockly from 'blockly/core';
import {
	CONDITION_CHECK,
	FIELD_LABELS,
	MATCH_LABELS,
	SPAM_BLOCKS,
	SPAM_FIELDS,
	SPAM_MATCHES
} from './rule';

/**
 * What the blocks of a spam filter are: five block types, the colours they wear and the flyout
 * they are dragged out of. Nothing in here touches a document, so the blocks can be exercised on
 * their own -- Blockly runs a workspace without a screen.
 *
 * Every block is a statement that ends there: it can be dropped into a well, and nothing can be
 * hung underneath it. So a well holds exactly one condition -- a column of blocks would leave open
 * which operator joins them -- and "und" and "oder" are wrappers with a well each side.
 */

/** The mail parts, in the order the dropdown offers them. */
const FIELD_OPTIONS = SPAM_FIELDS.map((field) => [FIELD_LABELS[field], field]);

/** The comparisons, likewise. */
const MATCH_OPTIONS = SPAM_MATCHES.map((match) => [MATCH_LABELS[match], match]);

/**
 * Saturated mid-tones with white lettering: the same three colours hold up on the light and the
 * dark dialog surface, which a block colour taken from the theme's own variables could not do --
 * Blockly parses these to derive the block's edges and only understands hex and rgb.
 */
const BLOCK_STYLES = {
	// Neutral for the frame and the operators, one colour for the comparisons: what a rule is
	// about are the comparisons, and "und" is scaffolding around them rather than another thing
	// to read.
	spam_root_blocks: { colourPrimary: '#18181b', colourTertiary: '#09090b' },
	spam_logic_blocks: { colourPrimary: '#52525b', colourTertiary: '#3f3f46' },
	spam_match_blocks: { colourPrimary: '#0f766e', colourTertiary: '#115e59' }
};

/**
 * The surfaces around the blocks do come from the theme's variables: Blockly writes these straight
 * into an inline style or into a stylesheet, where `var(--…)` resolves like anywhere else in the
 * app.
 *
 * Left unannotated on purpose: `inject` takes the theme structurally, and Blockly does not export
 * the interface behind it.
 */
export const SPAM_THEME = {
	name: 'overmail-spam',
	base: 'classic',
	blockStyles: BLOCK_STYLES,
	componentStyles: {
		workspaceBackgroundColour: 'var(--muted)',
		toolboxBackgroundColour: 'var(--card)',
		toolboxForegroundColour: 'var(--muted-foreground)',
		flyoutBackgroundColour: 'var(--card)',
		flyoutForegroundColour: 'var(--muted-foreground)',
		flyoutOpacity: 1,
		scrollbarColour: 'var(--muted-foreground)',
		scrollbarOpacity: 0.35,
		// An SVG filter and a rendering constant rather than a stylesheet declaration, so these two
		// stay concrete: a neutral grey that shows up against all three block colours.
		selectedGlowColour: '#a1a1aa',
		// Zelos blurs the whole block to mark the selection. Tightened to something closer to a
		// ring than a halo.
		selectedGlowSize: 0.4,
		insertionMarkerColour: '#a1a1aa'
	},
	// Points, not pixels -- that is what the renderer's stylesheet writes them as, so 10 here is
	// the ~13px the rest of the app calls small text.
	fontStyle: { family: '"DM Sans Variable", sans-serif', weight: '600', size: 10 }
};

/**
 * No categories: four blocks fit in one flyout, and a flyout that is always open is one less click.
 *
 * The gaps are spelled out because the flyout would otherwise put its one default gap after every
 * item alike, which reads as an even column of blocks with the group labels floating between them.
 * A label follows its group closely (the flyout's own gap, see `SpamFlyout`) and the last block of
 * a group carries the wider one.
 */
export const SPAM_TOOLBOX: Blockly.utils.toolbox.ToolboxInfo = {
	kind: 'flyoutToolbox',
	contents: [
		{ kind: 'label', text: 'Bedingung', id: undefined },
		{ kind: 'block', type: SPAM_BLOCKS.match, gap: 20 },
		{ kind: 'label', text: 'Verknüpfen', id: undefined },
		{ kind: 'block', type: SPAM_BLOCKS.and, gap: 8 },
		{ kind: 'block', type: SPAM_BLOCKS.or, gap: 8 },
		{ kind: 'block', type: SPAM_BLOCKS.not }
	]
};

/** Whether the block definitions are in Blockly's registry, which is global and outlives dialogs. */
let defined = false;

export function defineSpamBlocks() {
	if (defined) return;
	defined = true;

	Blockly.defineBlocksWithJsonArray([
		{
			type: SPAM_BLOCKS.root,
			message0: 'E-Mail ist Spam, wenn %1',
			args0: [{ type: 'input_statement', name: 'CONDITION', check: CONDITION_CHECK }],
			style: 'spam_root_blocks',
			tooltip:
				'Trifft die Bedingung zu, wird die Mail als Spam einsortiert. Dieser Baustein ist ' +
				'der Anfang der Regel und lässt sich nicht löschen.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.and,
			// A well takes a row of its own, and the words sit to the left of the one they belong
			// to -- so this reads down the block as the sentence it is.
			message0: '%1 und %2',
			args0: [
				{ type: 'input_statement', name: 'A', check: CONDITION_CHECK },
				{ type: 'input_statement', name: 'B', check: CONDITION_CHECK }
			],
			previousStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Trifft zu, wenn beide Bedingungen zutreffen.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.or,
			message0: '%1 oder %2',
			args0: [
				{ type: 'input_statement', name: 'A', check: CONDITION_CHECK },
				{ type: 'input_statement', name: 'B', check: CONDITION_CHECK }
			],
			previousStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Trifft zu, wenn mindestens eine der beiden Bedingungen zutrifft.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.not,
			message0: 'nicht %1',
			args0: [{ type: 'input_statement', name: 'OPERAND', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Kehrt die Bedingung um.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.match,
			message0: '%1 %2 %3',
			args0: [
				{ type: 'field_dropdown', name: 'FIELD', options: FIELD_OPTIONS },
				{ type: 'field_dropdown', name: 'MATCH', options: MATCH_OPTIONS },
				{ type: 'field_input', name: 'VALUE', text: '', spellcheck: false }
			],
			previousStatement: CONDITION_CHECK,
			style: 'spam_match_blocks',
			tooltip: 'Vergleicht einen Teil der Mail mit einem Text.',
			helpUrl: ''
		}
	]);
}
