import { forwardRef, type ButtonHTMLAttributes } from "react";
import { LoadingIndicator } from "./loading-indicator";

export type ButtonVariant = "primary" | "secondary" | "danger";

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  isLoading?: boolean;
  loadingLabel?: string;
  variant?: ButtonVariant;
}

const variantClasses: Record<ButtonVariant, string> = {
  primary:
    "border-primary bg-primary text-foreground-inverse shadow-primary hover:border-primary-hover hover:bg-primary-hover active:border-primary-hover active:bg-primary-hover disabled:border-surface-strong disabled:bg-surface-strong disabled:text-foreground-secondary disabled:shadow-none",
  secondary:
    "border-border-strong bg-surface text-foreground-secondary hover:border-primary hover:bg-surface-muted hover:text-foreground active:bg-surface-strong disabled:border-border disabled:bg-surface-muted disabled:text-foreground-muted",
  danger:
    "border-danger bg-danger text-foreground-inverse hover:brightness-90 active:brightness-75 disabled:border-danger/20 disabled:bg-danger/10 disabled:text-danger disabled:shadow-none disabled:hover:brightness-100",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  function Button(
    {
      children,
      className = "",
      disabled = false,
      isLoading = false,
      loadingLabel = "Loading",
      type = "button",
      variant = "primary",
      ...props
    },
    ref,
  ) {
    const isDisabled = disabled || isLoading;

    return (
      <button
        {...props}
        aria-busy={isLoading || undefined}
        className={`relative inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border px-4 py-2.5 text-sm font-semibold transition-[background-color,border-color,color,box-shadow,filter] duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:cursor-not-allowed motion-reduce:transition-none ${variantClasses[variant]} ${className}`}
        disabled={isDisabled}
        ref={ref}
        type={type}
      >
        <span
          aria-hidden={isLoading || undefined}
          className={isLoading ? "invisible" : ""}
        >
          {children}
        </span>
        {isLoading ? (
          <span className="absolute inset-0 flex items-center justify-center">
            <LoadingIndicator label={loadingLabel} size="small" />
          </span>
        ) : null}
      </button>
    );
  },
);

Button.displayName = "Button";
