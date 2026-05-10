/**
 * Renders a string with WC3 inline color codes
 * (`|cAARRGGBB...|r`) as a fragment of colored spans. Falls back to
 * plain text when the input has no codes. Safe against malformed
 * codes — anything that doesn't parse as a tag is rendered literally.
 */
import { parseWc3Color } from '../lib/wc3Color';

interface Props {
  text: string;
}

export default function ColoredText({ text }: Props) {
  const chunks = parseWc3Color(text);
  return (
    <>
      {chunks.map((c, i) => (
        c.color
          ? <span style={{ color: c.color }} key={i}>{c.text}</span>
          : <span key={i}>{c.text}</span>
      ))}
    </>
  );
}
