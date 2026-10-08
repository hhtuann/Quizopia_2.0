import { forwardRef, type HTMLAttributes } from "react";

export type SurfaceProps = HTMLAttributes<HTMLDivElement>;

export const Surface = forwardRef<HTMLDivElement, SurfaceProps>(
  function Surface({ className = "", ...props }, ref) {
    return (
      <div
        {...props}
        className={`rounded-xl border border-border bg-surface shadow-card ${className}`}
        ref={ref}
      />
    );
  },
);

Surface.displayName = "Surface";
