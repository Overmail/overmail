/**
 * A tag's colour, derived from its name rather than stored with it.
 *
 * Tags carry no colour on the server, and one that changed between sessions would make the list
 * unreadable -- the point of a coloured badge is that the same tag is the same colour every time.
 * Hashing the name gives exactly that for free.
 *
 * Lightness and chroma are fixed and the hue is all that varies: a palette that ranges over
 * lightness reads fine on one theme and washes out on the other, and this one knows nothing about
 * the theme. The badge mixes the colour into the surface rather than filling with it, see
 * `MailTagBadges`.
 */
export function tagColor(name: string): string {
	let hash = 0;
	for (const char of name.toLowerCase()) hash = (hash * 31 + char.charCodeAt(0)) | 0;

	return `oklch(0.68 0.14 ${Math.abs(hash) % 360})`;
}
