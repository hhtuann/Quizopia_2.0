import {
  forwardRef,
  useId,
  type InputHTMLAttributes,
  type ReactNode,
} from "react";

export interface TextFieldProps extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  "id"
> {
  error?: string;
  helperText?: string;
  id?: string;
  label: ReactNode;
}

export const TextField = forwardRef<HTMLInputElement, TextFieldProps>(
  function TextField(
    {
      "aria-describedby": ariaDescribedBy,
      className = "",
      error,
      helperText,
      id,
      label,
      ...props
    },
    ref,
  ) {
    const generatedId = useId();
    const inputId = id ?? `field-${generatedId}`;
    const helperId = helperText ? `${inputId}-helper` : undefined;
    const errorId = error ? `${inputId}-error` : undefined;
    const describedBy = [ariaDescribedBy, helperId, errorId]
      .filter(Boolean)
      .join(" ");

    return (
      <div className="space-y-2">
        <label
          className="block text-sm font-semibold text-foreground-secondary"
          htmlFor={inputId}
        >
          {label}
        </label>
        <input
          {...props}
          aria-describedby={describedBy || undefined}
          aria-invalid={error ? true : undefined}
          className={`min-h-11 w-full rounded-lg border bg-surface px-3 py-2.5 text-base text-foreground shadow-sm transition-[border-color,box-shadow] duration-200 placeholder:text-foreground-muted hover:border-border-strong focus-visible:border-focus focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-1 disabled:cursor-not-allowed disabled:border-border disabled:bg-surface-muted disabled:text-foreground-muted motion-reduce:transition-none ${error ? "border-danger" : "border-border-strong"} ${className}`}
          id={inputId}
          ref={ref}
        />
        {helperText ? (
          <p className="text-sm text-foreground-muted" id={helperId}>
            {helperText}
          </p>
        ) : null}
        {error ? (
          <p
            className="text-sm font-medium text-danger"
            id={errorId}
            role="alert"
          >
            {error}
          </p>
        ) : null}
      </div>
    );
  },
);

TextField.displayName = "TextField";
