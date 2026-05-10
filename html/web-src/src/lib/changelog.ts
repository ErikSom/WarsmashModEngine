/**
 * Tiny markdown converter for html/CHANGELOG.md. Handles the subset
 * the changelog actually uses:
 *   `#` / `##` headings
 *   `-` list items (with multi-line wrapped continuations)
 *   `**bold**`, `\`code\`` inline spans
 *   blank-line paragraph breaks
 *
 * Keeps us off a real markdown library for one file. Inputs that drift
 * outside this subset will render imperfectly but never throw.
 */

export function renderChangelog(md: string): string {
  const escape = (s: string): string =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

  // Bold first, then code (code spans are leaf — bold inside code stays literal).
  const inline = (s: string): string => escape(s)
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');

  const lines = md.split('\n');
  const out: string[] = [];
  let inList = false;
  const closeList = (): void => { if (inList) { out.push('</ul>'); inList = false; } };

  let i = 0;
  while (i < lines.length) {
    const line = lines[i].replace(/\s+$/, '');
    if (line.startsWith('# ')) {
      closeList();
      out.push('<h1>' + inline(line.slice(2)) + '</h1>');
      i++;
    }
    else if (line.startsWith('## ')) {
      closeList();
      out.push('<h2>' + inline(line.slice(3)) + '</h2>');
      i++;
    }
    else if (line.startsWith('- ')) {
      if (!inList) { out.push('<ul>'); inList = true; }
      let item = line.slice(2);
      i++;
      // Coalesce indented continuation lines into this list item, the
      // way real markdown treats wrapped bullets.
      while (i < lines.length) {
        const cont = lines[i];
        if (cont.length === 0) break;
        if (cont.startsWith('- ') || cont.startsWith('# ') || cont.startsWith('## ')) break;
        if (!(cont.startsWith(' ') || cont.startsWith('\t'))) break;
        item += ' ' + cont.replace(/^\s+/, '');
        i++;
      }
      out.push('<li>' + inline(item) + '</li>');
    }
    else if (line === '') {
      closeList();
      i++;
    }
    else {
      closeList();
      out.push('<p>' + inline(line) + '</p>');
      i++;
    }
  }
  closeList();
  return out.join('\n');
}
