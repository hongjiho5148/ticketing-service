const THEME_COUNT = 3;

export function posterThemeClass(id: number): string {
  return `poster-theme-${id % THEME_COUNT}`;
}

export function posterGlyph(title: string): string {
  const withoutYear = title.replace(/^\d{4}\s*/, "");
  const firstWord = withoutYear.split(/[\s[]/)[0];
  return firstWord.slice(0, 8);
}
