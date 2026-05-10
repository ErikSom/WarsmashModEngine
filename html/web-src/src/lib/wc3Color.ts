/**
 * WC3 color-code parsing. The engine uses inline tags of the form
 *   |cAARRGGBB<text>|r
 * where AARRGGBB is 8 hex digits (alpha + RGB). Tags can be nested
 * but most map authors keep them simple. Any unmatched / malformed
 * tag is treated as literal text.
 *
 * We expose two flavors:
 *   - parseWc3Color(s): structured chunks for rendering
 *   - stripWc3Color(s): plain string with all tags removed
 *
 * The mdx-m3-viewer w3i parser does NOT strip these for us — `name`,
 * `description`, etc. arrive verbatim with whatever the map author
 * encoded.
 */

export interface ColoredChunk {
  text: string;
  /** CSS color (e.g. `#ffaa00`), or undefined for default. */
  color?: string;
}

/** Parse a WC3-color-coded string into chunks. The output is always
 *  at least one chunk (possibly empty), and concatenating `text`
 *  fields yields the same result as `stripWc3Color()`. */
export function parseWc3Color(input: string): ColoredChunk[] {
  if (!input) return [{ text: '' }];
  const out: ColoredChunk[] = [];
  let buf = '';
  let currentColor: string | undefined;
  let i = 0;

  const flush = (): void => {
    if (buf.length === 0) return;
    out.push({ text: buf, color: currentColor });
    buf = '';
  };

  while (i < input.length) {
    if (input[i] === '|' && i + 1 < input.length) {
      const next = input[i + 1];
      if ((next === 'c' || next === 'C') && i + 10 <= input.length) {
        // Spec is 8 hex digits. Be tolerant: lower OR upper case.
        const aarrggbb = input.substr(i + 2, 8);
        if (/^[0-9a-fA-F]{8}$/.test(aarrggbb)) {
          flush();
          // Drop the alpha channel — CSS rendering looks fine with
          // just the RGB (and the alpha is usually 0xff anyway).
          currentColor = '#' + aarrggbb.slice(2).toLowerCase();
          i += 10;
          continue;
        }
      }
      if (next === 'r' || next === 'R') {
        flush();
        currentColor = undefined;
        i += 2;
        continue;
      }
      // |n is a literal newline tag in some maps. Skip the |n.
      if (next === 'n' || next === 'N') {
        buf += '\n';
        i += 2;
        continue;
      }
    }
    buf += input[i];
    i++;
  }
  flush();
  if (out.length === 0) out.push({ text: '' });
  return out;
}

export function stripWc3Color(input: string): string {
  return parseWc3Color(input).map(c => c.text).join('');
}
