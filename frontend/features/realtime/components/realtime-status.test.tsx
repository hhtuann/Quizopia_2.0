import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { RealtimeStatus } from "../hooks/use-realtime-subscriptions";
import { REALTIME_STATUS_OFF } from "../hooks/use-realtime-subscriptions";
import { RealtimeStatusInline } from "./realtime-status";
import { I18nProvider } from "../../../lib/i18n/provider";

const status = (
  state: RealtimeStatus["state"],
  attempts = 0,
): RealtimeStatus => ({ ...REALTIME_STATUS_OFF, state, attempts });

describe("Realtime status chip bilingual labels", () => {
  it("renders nothing while idle in either locale", () => {
    const { container, rerender } = render(
      <I18nProvider initialLocale="vi">
        <RealtimeStatusInline status={status("idle")} />
      </I18nProvider>,
    );
    expect(container).toBeEmptyDOMElement();
    rerender(
      <I18nProvider initialLocale="en">
        <RealtimeStatusInline status={status("idle")} />
      </I18nProvider>,
    );
    expect(container).toBeEmptyDOMElement();
  });

  it("localizes state labels and reconnect attempts in Vietnamese and English", () => {
    const vi = render(
      <I18nProvider initialLocale="vi">
        <RealtimeStatusInline status={status("connecting")} />
      </I18nProvider>,
    );
    expect(screen.getByText("Cập nhật trực tiếp: đang kết nối…")).toBeVisible();
    vi.unmount();

    const viRetrying = render(
      <I18nProvider initialLocale="vi">
        <RealtimeStatusInline status={status("reconnect-wait", 3)} />
      </I18nProvider>,
    );
    expect(
      screen.getByText("Cập nhật trực tiếp: đang kết nối lại… (lần thử 3)"),
    ).toBeVisible();
    viRetrying.unmount();

    render(
      <I18nProvider initialLocale="en">
        <RealtimeStatusInline status={status("reconnect-wait", 3)} />
      </I18nProvider>,
    );
    expect(
      screen.getByText("Live updates: reconnecting… (attempt 3)"),
    ).toBeVisible();
  });
});
