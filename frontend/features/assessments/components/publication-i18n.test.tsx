import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider, useI18n } from "../../../lib/i18n/provider";
import type { ClassroomApiClient } from "../../classrooms/api/classroom-api-client";
import type {
  PublicationApiClient,
  PublicationResult,
} from "../api/publication-api-client";
import type {
  Configuration,
  PublicationSummary,
  PublicationSummaryPage,
} from "../api/publication-contract.generated";
import { PublicationConfigurationForm } from "./publication-configuration-form";
import { PublicationDirectory } from "./publication-directory";

const classroomId = "f7b14962-3a2e-4d2d-926a-3b36ea90c199";
const classroomListClient = {
  list: vi.fn<ClassroomApiClient["list"]>(),
  get: vi.fn<ClassroomApiClient["get"]>(),
};
vi.mock("../../classrooms/use-classroom-client", () => ({
  useClassroomClient: () => classroomListClient,
}));

const summary: PublicationSummary = {
  publicationId: "e7b14962-3a2e-4d2d-926a-3b36ea90c199",
  title: "Algebra preparation",
  audience: "CLASS",
  state: "DRAFT",
  startAt: "2026-10-26T08:00:00Z",
  endAt: "2026-10-26T09:00:00Z",
  createdAt: "2026-10-25T08:00:00Z",
  revision: 0,
  quizId: "8ad4c564-3c27-4e6d-91aa-a004334aa8f8",
  quizVersionId: "9bd4c564-3c27-4e6d-91aa-a004334aa8f7",
  versionNumber: 3,
};
function directoryClient() {
  const api: PublicationApiClient = {
    list: vi.fn<PublicationApiClient["list"]>(),
    get: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    open: vi.fn(),
    close: vi.fn(),
    extend: vi.fn(),
  };
  return api;
}
const page = (
  items: PublicationSummary[],
  nextCursor: string | null = null,
): PublicationResult<PublicationSummaryPage> => ({
  ok: true,
  value: { items, nextCursor },
});

const configuration: Configuration = {
  title: "Network fundamentals",
  description: null,
  quizId: summary.quizId,
  quizVersionId: summary.quizVersionId,
  versionNumber: 3,
  audience: "CLASS",
  classroomId,
  startAt: "2026-10-26T08:00:00.000Z",
  endAt: "2026-10-26T09:00:00.000Z",
  durationMinutes: 30,
  monitoringChoice: "UNCONFIGURED",
  maxAttempts: 1,
};

/** Renders children inside the live provider plus a locale toggle control. */
function LocaleHarness({
  children,
  initialLocale,
}: {
  readonly children: React.ReactNode;
  readonly initialLocale: "vi" | "en";
}) {
  return (
    <I18nProvider initialLocale={initialLocale}>
      {children}
      <LocaleToggle />
    </I18nProvider>
  );
}
function LocaleToggle() {
  const { locale, setLocale } = useI18n();
  return (
    <button
      onClick={() => setLocale(locale === "en" ? "vi" : "en")}
      type="button"
    >
      switch-locale
    </button>
  );
}

beforeEach(() => {
  classroomListClient.list.mockReset();
  classroomListClient.get.mockReset();
  classroomListClient.list.mockResolvedValue({
    ok: true,
    value: {
      items: [
        {
          id: classroomId,
          name: "Networks 12A",
          subject: "Computer Science",
          grade: "12",
          description: null,
          createdAt: "2026-10-25T08:00:00Z",
        },
      ],
      nextCursor: null,
    },
  });
  classroomListClient.get.mockResolvedValue({
    ok: true,
    value: {
      id: classroomId,
      name: "Networks 12A",
      subject: "Computer Science",
      grade: "12",
      description: null,
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.setAttribute("open", "");
    },
  });
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    configurable: true,
    value: function (this: HTMLDialogElement) {
      this.removeAttribute("open");
    },
  });
});

describe("assessment i18n (vi default)", () => {
  it("renders the My Assessments directory in Vietnamese without a cookie", async () => {
    const api = directoryClient();
    vi.mocked(api.list).mockResolvedValue(page([summary], "opaque"));
    render(
      <I18nProvider initialLocale="vi">
        <PublicationDirectory client={api} />
      </I18nProvider>,
    );
    expect(
      await screen.findByRole("link", { name: "Quản lý Algebra preparation" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Bài kiểm tra của tôi" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Tìm bài kiểm tra")).toBeInTheDocument();
    expect(screen.getByLabelText("Trạng thái Publication")).toHaveValue("");
    // Canonical API enums stay verbatim in the state filter and badge.
    expect(screen.getAllByText("DRAFT").length).toBeGreaterThan(0);
    expect(screen.getByText("Phiên bản bộ câu hỏi 3")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Tải thêm bài kiểm tra" }),
    ).toBeInTheDocument();
    expect(screen.getByText("Đã hiển thị 1 bài kiểm tra")).toBeInTheDocument();
    expect(screen.getByText("Bắt đầu:")).toBeInTheDocument();
  });

  it("renders the owned Classroom selector in Vietnamese", async () => {
    render(
      <I18nProvider initialLocale="vi">
        <PublicationConfigurationForm
          initial={{ ...configuration, classroomId: null }}
          mode="create"
          onSubmit={() => {}}
        />
      </I18nProvider>,
    );
    expect(
      await screen.findByRole("group", { name: "Chọn một lớp học" }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        "Chỉ hiển thị lớp học do bạn sở hữu. Hãy chọn một lớp cho bài kiểm tra này.",
      ),
    ).toBeInTheDocument();
    // Classroom identity fields are user content and never translate.
    expect(
      await screen.findByRole("radio", { name: /Networks 12A/ }),
    ).toBeInTheDocument();
  });
});

describe("assessment i18n (mid-form locale switch)", () => {
  it("preserves entered fields, selected Classroom and re-localizes errors on the next render", async () => {
    const submit = vi.fn();
    render(
      <LocaleHarness initialLocale="en">
        <PublicationConfigurationForm
          initial={{ ...configuration, classroomId: null }}
          mode="create"
          onSubmit={submit}
        />
      </LocaleHarness>,
    );
    const classroom = await screen.findByRole("radio", {
      name: /Networks 12A/,
    });
    fireEvent.click(classroom);
    await waitFor(() => expect(classroom).toBeChecked());
    fireEvent.change(screen.getByLabelText("Publication title"), {
      target: { value: "Keep my draft" },
    });
    fireEvent.change(screen.getByLabelText("Attempt duration (minutes)"), {
      target: { value: "0" },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Review and create DRAFT" }),
    );
    expect(
      screen.getByText("Enter a whole number from 5 to 240 minutes."),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "switch-locale" }));
    // All entered values survive the live locale switch.
    expect(screen.getByLabelText("Tiêu đề Publication")).toHaveValue(
      "Keep my draft",
    );
    expect(screen.getByRole("radio", { name: /Networks 12A/ })).toBeChecked();
    expect(screen.getByLabelText("Thời lượng Attempt (phút)")).toHaveValue(0);
    // The stored English error re-localizes on the next render.
    expect(
      screen.getByText("Nhập một số nguyên từ 5 đến 240 phút."),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Xem lại và tạo DRAFT" }),
    ).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();
  });
});

describe("assessment i18n (PUBLIC clears the Classroom)", () => {
  it.each(["en", "vi"] as const)(
    "clears classroomId and monitoring when switching to PUBLIC under %s",
    async (locale) => {
      const submit = vi.fn();
      render(
        <LocaleHarness initialLocale={locale}>
          <PublicationConfigurationForm
            initial={configuration}
            mode="edit"
            onSubmit={submit}
          />
        </LocaleHarness>,
      );
      // Wait for the saved CLASS selection to verify as listed/valid.
      await screen.findByRole("radio", { name: /Networks 12A/ });
      const publicLabel = locale === "vi" ? "Công khai" : "Public";
      fireEvent.click(screen.getByRole("radio", { name: publicLabel }));
      expect(
        screen.queryByRole("group", {
          name: locale === "vi" ? "Chọn một lớp học" : "Choose one Classroom",
        }),
      ).not.toBeInTheDocument();
      const reviewLabel =
        locale === "vi" ? "Xem lại thay đổi DRAFT" : "Review DRAFT changes";
      fireEvent.click(screen.getByRole("button", { name: reviewLabel }));
      const confirmLabel = locale === "vi" ? "Lưu DRAFT" : "Save DRAFT";
      expect(screen.getByRole("button", { name: confirmLabel })).toBeEnabled();
      fireEvent.click(screen.getByRole("button", { name: confirmLabel }));
      await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
      expect(submit).toHaveBeenCalledWith(
        expect.objectContaining({
          audience: "PUBLIC",
          classroomId: null,
          monitoringChoice: "DISABLED",
        }),
      );
    },
  );
});
