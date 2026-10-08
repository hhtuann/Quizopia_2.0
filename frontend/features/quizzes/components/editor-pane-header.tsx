interface EditorPaneHeaderProps {
  readonly className?: string;
  readonly description: string;
  readonly descriptionId: string;
  readonly htmlFor?: string;
  readonly title: string;
  readonly titleId: string;
}

export function EditorPaneHeader({
  className = "",
  description,
  descriptionId,
  htmlFor,
  title,
  titleId,
}: EditorPaneHeaderProps) {
  const titleClasses =
    "block font-heading text-base font-bold leading-6 text-foreground";

  return (
    <div className={`min-h-[4.5rem] shrink-0 ${className}`}>
      {htmlFor ? (
        <label className={titleClasses} htmlFor={htmlFor} id={titleId}>
          {title}
        </label>
      ) : (
        <h2 className={titleClasses} id={titleId}>
          {title}
        </h2>
      )}
      <p
        className="mt-1 text-sm leading-5 text-foreground-muted"
        id={descriptionId}
      >
        {description}
      </p>
    </div>
  );
}
