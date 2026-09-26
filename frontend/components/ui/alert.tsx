import type { HTMLAttributes, ReactNode } from "react";

export type AlertVariant = "info" | "success" | "warning" | "danger";

export interface AlertProps extends Omit<
  HTMLAttributes<HTMLDivElement>,
  "title"
> {
  title: ReactNode;
  variant?: AlertVariant;
}

const variantClasses: Record<AlertVariant, string> = {
  info: "border-info bg-info/5",
  success: "border-success bg-success/5",
  warning: "border-warning bg-warning/5",
  danger: "border-danger bg-danger/5",
};

export function Alert({
  children,
  className = "",
  role,
  title,
  variant = "info",
  ...props
}: AlertProps) {
  return (
    <div
      {...props}
      className={`rounded-lg border border-l-4 p-4 text-sm text-foreground-secondary ${variantClasses[variant]} ${className}`}
      role={role ?? (variant === "danger" ? "alert" : "status")}
    >
      <p className="font-semibold text-foreground">{title}</p>
      <div className="mt-1 leading-6">{children}</div>
    </div>
  );
}
