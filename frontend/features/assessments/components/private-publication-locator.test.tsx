import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { I18nProvider } from "../../../lib/i18n/provider";
import { PrivatePublicationLocator } from "./private-publication-locator";

describe("private locator", () => {
  it.each(["en", "vi"] as const)(
    "copies the full masked value and reports clipboard failure in %s",
    async (locale) => {
      const writeText = vi
        .fn()
        .mockRejectedValue(new Error("clipboard unavailable"));
      Object.defineProperty(navigator, "clipboard", {
        configurable: true,
        value: { writeText },
      });
      const locator = "Ab_".repeat(14) + "Z";
      render(
        <I18nProvider initialLocale={locale}>
          <PrivatePublicationLocator locator={locator} />
        </I18nProvider>,
      );
      expect(screen.queryByText(locator)).not.toBeInTheDocument();
      fireEvent.click(
        screen.getByRole("button", {
          name: locale === "en" ? "Copy locator" : "Sao chép locator",
        }),
      );
      expect(
        await screen.findByText(
          locale === "en"
            ? "Copy failed. Reveal the locator and copy it manually."
            : "Không sao chép được. Hãy hiện locator rồi sao chép thủ công.",
        ),
      ).toBeInTheDocument();
      expect(writeText).toHaveBeenCalledWith(locator);
      fireEvent.click(
        screen.getByRole("button", {
          name: locale === "en" ? "Show locator" : "Hiện locator",
        }),
      );
      expect(screen.getByText(locator)).toBeInTheDocument();
      expect(
        screen.getByRole("button", {
          name: locale === "en" ? "Hide locator" : "Ẩn locator",
        }),
      ).toHaveAttribute("aria-pressed", "true");
    },
  );
});
