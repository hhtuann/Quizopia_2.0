/**
 * Shared decorative brand orb for prominent product hero banners.
 *
 * One canonical subtle Indigo-to-Violet blurred orb, positioned top-right and
 * kept outside dense editing/reading areas (DESIGN.md §3.2, §19). Using the
 * same component everywhere keeps hero decoration identical between screens
 * instead of each page inventing its own gradient treatment.
 */
export function HeroOrb({ className = "" }: { readonly className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={`pointer-events-none absolute rounded-full bg-gradient-to-br from-primary/10 to-secondary/10 blur-3xl ${className}`}
    />
  );
}
