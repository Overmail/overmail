import * as Blockly from 'blockly/core';
import {
	CONDITION_CHECK,
	FIELD_LABELS,
	GROUP_LABELS,
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
 * Every block is a statement rather than something plugged into a socket: the root and the three
 * wrappers hold a column of conditions, one per line, and a condition connects above and below
 * only to other conditions.
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
	spam_root_blocks: { colourPrimary: '#dc2626', colourTertiary: '#991b1b' },
	spam_logic_blocks: { colourPrimary: '#7c3aed', colourTertiary: '#5b21b6' },
	spam_match_blocks: { colourPrimary: '#0d9488', colourTertiary: '#0f766e' }
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
		insertionMarkerColour: '#a1a1aa'
	},
	fontStyle: { family: '"DM Sans Variable", sans-serif', weight: '600', size: 11 },
	// The root block has neither a previous connection nor a next one, so it -- and only it -- gets
	// the hat, which is exactly the "everything inside me is one rule" it stands for.
	startHats: true
};

/** No categories: four blocks fit in one flyout, and a flyout that is always open is one less click. */
export const SPAM_TOOLBOX: Blockly.utils.toolbox.ToolboxInfo = {
	kind: 'flyoutToolbox',
	contents: [
		{ kind: 'label', text: 'Bedingung', id: undefined },
		{ kind: 'block', type: SPAM_BLOCKS.match },
		{ kind: 'label', text: 'Verknüpfen', id: undefined },
		{ kind: 'block', type: SPAM_BLOCKS.and },
		{ kind: 'block', type: SPAM_BLOCKS.or },
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
			args0: [{ type: 'input_statement', name: 'CONDITIONS', check: CONDITION_CHECK }],
			style: 'spam_root_blocks',
			tooltip:
				'Treffen alle Bedingungen darin zu, wird die Mail als Spam einsortiert. Dieser ' +
				'Baustein ist der Anfang der Regel und lässt sich nicht löschen.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.and,
			message0: `${GROUP_LABELS[SPAM_BLOCKS.and]} %1`,
			args0: [{ type: 'input_statement', name: 'CONDITIONS', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Trifft zu, wenn jede Bedingung darin zutrifft.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.or,
			message0: `${GROUP_LABELS[SPAM_BLOCKS.or]} %1`,
			args0: [{ type: 'input_statement', name: 'CONDITIONS', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Trifft zu, wenn mindestens eine Bedingung darin zutrifft.',
			helpUrl: ''
		},
		{
			type: SPAM_BLOCKS.not,
			message0: `${GROUP_LABELS[SPAM_BLOCKS.not]} %1`,
			args0: [{ type: 'input_statement', name: 'CONDITIONS', check: CONDITION_CHECK }],
			previousStatement: CONDITION_CHECK,
			nextStatement: CONDITION_CHECK,
			style: 'spam_logic_blocks',
			tooltip: 'Trifft zu, solange keine Bedingung darin zutrifft.',
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
			nextStatement: CONDITION_CHECK,
			style: 'spam_match_blocks',
			tooltip: 'Vergleicht einen Teil der Mail mit einem Text.',
			helpUrl: ''
		}
	]);
}
