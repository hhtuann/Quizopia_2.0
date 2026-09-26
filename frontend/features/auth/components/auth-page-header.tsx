interface AuthPageHeaderProps {
  readonly description: string;
  readonly title: string;
}

export function AuthPageHeader({ description, title }: AuthPageHeaderProps) {
  return (
    <header>
      <p className="text-sm font-semibold text-primary">Quizopia account</p>
      <h1 className="mt-2 text-3xl font-bold tracking-[-0.02em] text-foreground">
        {title}
      </h1>
      <p className="mt-3 text-base leading-7 text-foreground-secondary">
        {description}
      </p>
    </header>
  );
}
