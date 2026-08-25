import type * as Blockly from 'blockly/core';

/** The part of a mail a comparison reads. */
export const SPAM_FIELDS = ['subject', 'sender_name', 'sender_address', 'body'] as const;

export type SpamField = (typeof SPAM_FIELDS)[number];

/** How a comparison holds that part against its text. */
export const SPAM_MATCHES = ['equals', 'contains', 'regex'] as const;

export type SpamMatch = (typeof SPAM_MATCHES)[number];

/**
 * A filter as the blocks spell it out: boolean operators over comparisons on one mail, which is
 * spam when the tree comes out true. `op` is the tag on every node, so a rule can be walked --
 * and read by whoever ends up applying it -- without knowing which shape it is beforehand.
 *
 * `and` and `or` take as many operands as the block they came from had conditions stacked in it,
 * which is one for one what the editor shows: a wrapper with a column of conditions inside.
 */
export type SpamRule =
	| { op: 'and' | 'or'; operands: SpamRule[] }
	| { op: 'not'; operand: SpamRule }
	| { op: 'match'; field: SpamField; match: SpamMatch; value: string };

/** A named filter, as the dialog hands it over. */
export type SpamFilter = {
	name: string;
	rule: SpamRule;
};

/** The block types the editor knows, in one place: both the blocks and the reader name them. */
export const SPAM_BLOCKS = {
	root: 'spam_root',
	and: 'spam_and',
	or: 'spam_or',
	not: 'spam_not',
	match: 'spam_match'
} as const;

/** The one statement type in this editor: every block is a condition, and only those connect. */
export const CONDITION_CHECK = 'SpamCondition';

/** What the mail parts are called on the blocks and in the sentence under the editor. */
export const FIELD_LABELS: Record<SpamField, string> = {
	subject: 'Betreff',
	sender_name: 'Absendername',
	sender_address: 'Absenderadresse',
	body: 'Inhalt'
};

/** Same for the comparisons. Written so that `<field> <match> "<value>"` reads as a sentence. */
export const MATCH_LABELS: Record<SpamMatch, string> = {
	equals: 'ist gleich',
	contains: 'enthält',
	regex: 'passt auf Regex'
};

/**
 * What the three wrappers are called. They say how the column of conditions inside them is read,
 * which is also the only way to word what is wrong with an empty one.
 */
export const GROUP_LABELS = {
	[SPAM_BLOCKS.and]: 'alle davon',
	[SPAM_BLOCKS.or]: 'eines davon',
	[SPAM_BLOCKS.not]: 'keines davon'
} as const;

/** What came out of the workspace, and what to tell the user about it. */
export type RuleReadout = {
	/** The filter as it stands, or null while the blocks do not add up to one. */
	rule: SpamRule | null;
	/** Why `rule` is null, in the words the dialog puts on screen. */
	problem: string | null;
	/** Worth saying, but no reason to hold the filter back. */
	warning: string | null;
};

/**
 * Why the tree does not add up yet. Thrown rather than returned so that the walk below stays a
 * plain recursion over the blocks instead of threading a result type through every branch; the
 * message is what the dialog shows, so it is written for the user.
 */
class Unfinished extends Error {}

/** Reads the rule out of the wrapper block every workspace is built around. */
export function readRule(workspace: Blockly.Workspace): RuleReadout {
	const [root, ...rest] = workspace.getBlocksByType(SPAM_BLOCKS.root, true);

	// Loose blocks are no reason to stop -- they are how you park a condition while rearranging --
	// but they are also easy to forget about, so they get said out loud. A second root would be
	// one too, except that nothing can make one: the toolbox does not offer it.
	const strays = workspace.getTopBlocks(false).filter((block) => block !== root).length;
	const warning =
		strays === 0
			? null
			: strays === 1
				? 'Ein Baustein liegt frei herum und gehört nicht zur Regel.'
				: `${strays} Bausteine liegen frei herum und gehören nicht zur Regel.`;

	if (!root || rest.length > 0) {
		return { rule: null, problem: 'Der Regel fehlt ihr Anfang.', warning };
	}

	try {
		const conditions = readStack(root.getInputTargetBlock('CONDITIONS'));

		// An empty root is where every filter starts, so it is worth naming what goes in it rather
		// than reporting it as a hole.
		if (conditions.length === 0) {
			return { rule: null, problem: 'Der Regel fehlt noch eine Bedingung.', warning };
		}

		return { rule: all(conditions), problem: null, warning };
	} catch (error) {
		if (error instanceof Unfinished) return { rule: null, problem: error.message, warning };
		throw error;
	}
}

/**
 * A column of conditions, top to bottom. Every wrapper holds one, and so does the root -- which is
 * why a condition is a statement here rather than something plugged into a socket: a filter is
 * read as a list, and a list is what the editor shows.
 */
function readStack(first: Blockly.Block | null): SpamRule[] {
	const rules: SpamRule[] = [];

	for (let block = first; block; block = block.getNextBlock()) {
		// The grey preview of a block being dragged, and a block the user has switched off. Neither
		// is part of the rule: one is not placed yet, the other is placed and meant to sit this one
		// out, which is what every Blockly editor takes a disabled block to mean.
		if (block.isInsertionMarker() || !block.isEnabled()) continue;

		rules.push(readCondition(block));
	}

	return rules;
}

function readCondition(block: Blockly.Block): SpamRule {
	switch (block.type) {
		case SPAM_BLOCKS.and:
			return all(readGroup(block));

		case SPAM_BLOCKS.or:
			return { op: 'or', operands: readGroup(block) };

		// "None of these" is "not any of these" -- said with the two operators the rule already
		// has, so that whoever applies it does not need a third.
		case SPAM_BLOCKS.not:
			return { op: 'not', operand: any(readGroup(block)) };

		case SPAM_BLOCKS.match:
			return readMatch(block);

		default:
			throw new Unfinished(`Der Baustein „${block.type}“ gehört nicht in einen Spamfilter.`);
	}
}

/** The conditions inside a wrapper. Empty is unfinished: a wrapper says nothing on its own. */
function readGroup(block: Blockly.Block): SpamRule[] {
	const conditions = readStack(block.getInputTargetBlock('CONDITIONS'));

	if (conditions.length === 0) {
		const label = GROUP_LABELS[block.type as keyof typeof GROUP_LABELS];
		throw new Unfinished(`„${label}“ ist noch leer.`);
	}

	return conditions;
}

/** A column of conditions holds together by "and" -- one on its own needs no operator at all. */
function all(conditions: SpamRule[]): SpamRule {
	return conditions.length === 1 ? conditions[0] : { op: 'and', operands: conditions };
}

function any(conditions: SpamRule[]): SpamRule {
	return conditions.length === 1 ? conditions[0] : { op: 'or', operands: conditions };
}

function readMatch(block: Blockly.Block): SpamRule {
	const field = block.getFieldValue('FIELD') as SpamField;
	const match = block.getFieldValue('MATCH') as SpamMatch;
	const value = block.getFieldValue('VALUE') as string;

	if (value.length === 0) {
		throw new Unfinished(`„${FIELD_LABELS[field]} ${MATCH_LABELS[match]}“ hat noch keinen Text.`);
	}

	// The rule is meant to run somewhere else later on, so a regex that no engine can compile has
	// to be caught here rather than at the first mail it is held against.
	if (match === 'regex') {
		try {
			new RegExp(value);
		} catch {
			throw new Unfinished(`„${value}“ ist kein gültiger Regex.`);
		}
	}

	return { op: 'match', field, match, value };
}

/**
 * The rule as one German sentence. The blocks say the same thing, but they say it in two
 * dimensions -- this is the line to read back before saving the filter.
 */
export function describeRule(rule: SpamRule): string {
	switch (rule.op) {
		case 'match':
			return `${FIELD_LABELS[rule.field]} ${MATCH_LABELS[rule.match]} „${rule.value}“`;
		case 'not':
			return `nicht ${nested(rule.operand)}`;
		case 'and':
		case 'or':
			return rule.operands.map(nested).join(rule.op === 'and' ? ' und ' : ' oder ');
	}
}

/** Brackets around anything that is itself made of parts, so `a und (b oder c)` stays readable. */
function nested(rule: SpamRule): string {
	return rule.op === 'match' ? describeRule(rule) : `(${describeRule(rule)})`;
}

/**
 * The workspace the editor starts from: the root block, with `rule` already stacked inside it when
 * the dialog was handed one. Plain JSON in Blockly's own serialization format, so seeding the
 * editor and reopening a saved filter are the same code path.
 */
export function workspaceStateFor(rule: SpamRule | null): Record<string, unknown> {
	// The root reads its conditions as one "and", so a rule that is one goes straight into it
	// rather than into a wrapper the root would only repeat.
	const conditions = rule === null ? [] : rule.op === 'and' ? rule.operands : [rule];

	return {
		blocks: {
			languageVersion: 0,
			blocks: [
				{
					type: SPAM_BLOCKS.root,
					// Clear of the flyout, which the workspace's origin already sits to the right of.
					x: 40,
					y: 32,
					inputs: conditions.length > 0 ? { CONDITIONS: { block: stackStateFor(conditions) } } : {}
				}
			]
		}
	};
}

/** One condition per block, each hanging off the one above it. */
function stackStateFor(conditions: SpamRule[]): Record<string, unknown> {
	const [first, ...rest] = conditions;
	const state = blockStateFor(first);

	if (rest.length > 0) state.next = { block: stackStateFor(rest) };

	return state;
}

function blockStateFor(rule: SpamRule): Record<string, unknown> {
	switch (rule.op) {
		case 'match':
			return {
				type: SPAM_BLOCKS.match,
				fields: { FIELD: rule.field, MATCH: rule.match, VALUE: rule.value }
			};
		case 'not':
			return {
				type: SPAM_BLOCKS.not,
				inputs: { CONDITIONS: { block: blockStateFor(rule.operand) } }
			};
		case 'and':
		case 'or':
			return {
				type: rule.op === 'and' ? SPAM_BLOCKS.and : SPAM_BLOCKS.or,
				inputs: { CONDITIONS: { block: stackStateFor(rule.operands) } }
			};
	}
}
