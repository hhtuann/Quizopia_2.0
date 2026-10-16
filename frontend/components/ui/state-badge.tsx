import type { HTMLAttributes } from "react";

export type StateBadgeVariant = "info" | "success" | "warning" | "neutral";

export interface StateBadgeProps extends HTMLAttributes<HTMLSpanElement> {
  readonly variant?: StateBadgeVariant;
}

const variantClasses: Record<StateBadgeVariant, string> = {
  info: "border-info/30 bg-info/10 text-info-strong",
  success: "border-success/30 bg-success/10 text-success-strong",
  warning: "border-warning/40 bg-warning/10 text-warning-strong",
  neutral: "border-border bg-surface-muted text-foreground-secondary",
};

/**
 * Compact status pill used by workflow-driven screens. It is presentation
 * only: the label always comes from server-returned state.
 */
export function StateBadge({
  className = "",
  variant = "neutral",
  ...props
}: StateBadgeProps) {
  return (
    <span
      {...props}
      className={`inline-flex items-center rounded-full border px-3 py-1 text-xs font-semibold ${variantClasses[variant]} ${className}`}
    />
  );
}
