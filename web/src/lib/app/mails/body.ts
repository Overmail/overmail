import type { MailContent } from '$lib/repository/MailRepository';

/**
 * A mail's body as the text to show.
 *
 * The plain text part when the mail carried one, and the HTML part flattened when it did not:
 * plenty of mail is HTML only, and those would otherwise read as empty. Flattened rather than
 * rendered -- rendering a stranger's markup means sanitising it, and the card shows text.
 */
export function mailBodyText(content: MailContent): string {
	const text = content.text?.trim();
	if (text) return text;

	return content.html ? flattenHtml(content.html) : '';
}

/** Tags whose content is markup or code rather than something to read. */
const DROPPED_ELEMENTS = /<(script|style|head|title)\b[^>]*>[\s\S]*?<\/\1>/gi;

/** Tags that end a line where they sit, so the flattened text keeps the mail's paragraphs. */
const LINE_BREAKING = /<(?:br|\/p|\/div|\/tr|\/li|\/h[1-6]|\/blockquote)\b[^>]*>/gi;

/** The five that have to be escaped in HTML, plus the space that pads half of all mail layouts. */
const ENTITIES: Record<string, string> = {
	amp: '&',
	lt: '<',
	gt: '>',
	quot: '"',
	apos: "'",
	nbsp: ' '
};

function flattenHtml(html: string): string {
	const withBreaks = html.replace(DROPPED_ELEMENTS, '').replace(LINE_BREAKING, '\n');

	return decodeEntities(withBreaks.replace(/<[^>]*>/g, ''))
		// Every layout table leaves runs of whitespace behind where its cells were.
		.replace(/[^\S\n]+/g, ' ')
		.replace(/ ?\n ?/g, '\n')
		.replace(/\n{3,}/g, '\n\n')
		.trim();
}

function decodeEntities(text: string): string {
	return text.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (entity, name: string) => {
		const named = ENTITIES[name.toLowerCase()];
		if (named !== undefined) return named;

		if (name.startsWith('#')) {
			const hex = name[1] === 'x' || name[1] === 'X';
			const code = Number.parseInt(hex ? name.slice(2) : name.slice(1), hex ? 16 : 10);
			if (Number.isFinite(code) && code > 0) return String.fromCodePoint(code);
		}

		// Something we do not know: left as it stood rather than swallowed.
		return entity;
	});
}
