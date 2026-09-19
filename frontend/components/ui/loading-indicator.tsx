import type { HTMLAttributes } from "react";

export interface LoadingIndicatorProps extends HTMLAttributes<HTMLSpanElement> {
  label?: string;
  size?: "small" | "medium";
}

export function LoadingIndicator({
  className = "",
  label = "Loading",
  size = "medium",
  ...props
}: LoadingIndicatorProps) {
  const sizeClass =
    size === "small" ? "size-4 border-2" : "size-6 border-[3px]";

  return (
    <span
      {...props}
      className={`inline-flex items-center justify-center ${className}`}
      role="status"
    >
      <span
        aria-hidden="true"
        className={`${sizeClass} animate-spin rounded-full border-current border-r-transparent motion-reduce:animate-none`}
      />
      <span className="sr-only">{label}</span>
    </span>
  );
}
