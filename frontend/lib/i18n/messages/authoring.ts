// Quiz authoring domain catalog. Keys are the exact rendered English source
// text; values are Vietnamese presentation only. Teacher-authored Quiz
// Markdown, canonical grammar markers (Câu, Đáp án, Lời giải), question-type
// enums, Quiz ID/UUID tokens and backend diagnostics never pass through here.
export const authoringMessages = {
  // Quiz Library hero, filters, cards, empty/loading/error/pagination
  "Create a draft, write your quiz, preview it, and publish.":
    "Tạo bản nháp, soạn bài, xem trước rồi xuất bản.",
  "Create quiz": "Tạo bộ câu hỏi",
  "Your quizzes": "Bộ câu hỏi của bạn",
  "Manage your quizzes.": "Quản lý các bộ câu hỏi của bạn.",
  "Loading quiz library": "Đang tải thư viện bộ câu hỏi",
  "Loading quiz library…": "Đang tải thư viện bộ câu hỏi…",
  "Quiz library authentication required":
    "Cần đăng nhập để xem thư viện bộ câu hỏi",
  "Quiz library access denied": "Truy cập thư viện bộ câu hỏi bị từ chối",
  "Quiz library could not be loaded": "Không thể tải thư viện bộ câu hỏi",
  "Quizopia could not connect. Your loaded quizzes stay on this page; try again.":
    "Quizopia không thể kết nối. Các bộ câu hỏi đã tải vẫn còn trên trang; hãy thử lại.",
  "No quizzes yet": "Chưa có bộ câu hỏi nào",
  "Create your first quiz to start authoring and publishing Quiz Markdown.":
    "Tạo bộ câu hỏi đầu tiên để bắt đầu soạn và xuất bản Quiz Markdown.",
  "Create your first quiz": "Tạo bộ câu hỏi đầu tiên",
  "More quizzes could not be loaded": "Không thể tải thêm bộ câu hỏi",
  "Loading more quizzes": "Đang tải thêm bộ câu hỏi",
  "Load more": "Tải thêm",
  "All quizzes loaded.": "Đã tải toàn bộ bộ câu hỏi.",
  "Untitled quiz": "Bộ câu hỏi chưa đặt tên",
  "Quiz ID: {quizId}": "Mã bộ câu hỏi: {quizId}",
  "Draft only": "Chỉ bản nháp",
  "Latest version {number}": "Phiên bản mới nhất {number}",
  Created: "Ngày tạo",
  Updated: "Ngày cập nhật",
  "Back to Quiz Library": "Quay lại thư viện bộ câu hỏi",
  // Shared authoring API failure presentation
  "Quizopia could not connect. Your editor content is still here; try again.":
    "Quizopia không thể kết nối. Nội dung trình soạn của bạn vẫn còn; hãy thử lại.",
  "The Quiz Markdown has errors. Review the details and try again.":
    "Quiz Markdown có lỗi. Hãy xem lại chi tiết và thử lại.",
  "Quizopia got an unexpected response ({status}).":
    "Quizopia nhận được phản hồi không mong đợi ({status}).",
  // Create-on-first-save workspace (FB-014)
  "Quiz could not be created": "Không thể tạo bộ câu hỏi",
  "First save needs a question": "Lần lưu đầu tiên cần một câu hỏi",
  "Add at least one recognizable question, for example {example}, then save again.":
    "Hãy thêm ít nhất một câu hỏi nhận dạng được, ví dụ {example}, rồi lưu lại.",
  "Quiz creation is not duplicate-protected yet. If the failed attempt reached the server, saving again may create a second quiz — check your library before retrying.":
    "Việc tạo bộ câu hỏi chưa được bảo vệ chống trùng lặp. Nếu lần gửi thất bại đã tới hệ thống, lưu lại có thể tạo bộ câu hỏi thứ hai — hãy kiểm tra thư viện trước khi thử lại.",
  // Focused editor chrome and states
  "You have unsaved changes. Leave the editor and discard them?":
    "Bạn có thay đổi chưa lưu. Rời trình soạn và bỏ các thay đổi đó?",
  "Unable to save the draft. Check your connection and retry.":
    "Không thể lưu bản nháp. Hãy kiểm tra kết nối và thử lại.",
  "The draft changed while saving. Save your latest changes before publishing.":
    "Bản nháp đã thay đổi trong lúc lưu. Hãy lưu các thay đổi mới nhất trước khi xuất bản.",
  "Unable to publish. Check your connection and retry.":
    "Không thể xuất bản. Hãy kiểm tra kết nối và thử lại.",
  "Quiz Markdown has errors. Select an item to jump to its source.":
    "Quiz Markdown có lỗi. Chọn một mục để nhảy tới vị trí nguồn.",
  "Published version {version}.": "Đã xuất bản phiên bản {version}.",
  "Version {version} already matches this unchanged draft.":
    "Phiên bản {version} đã trùng với bản nháp không thay đổi này.",
  "Loading quiz draft": "Đang tải bản nháp bộ câu hỏi",
  "Loading quiz draft…": "Đang tải bản nháp bộ câu hỏi…",
  "Quiz access denied": "Truy cập bộ câu hỏi bị từ chối",
  "You cannot edit this quiz": "Bạn không thể chỉnh sửa bộ câu hỏi này",
  "You do not have permission to edit this quiz.":
    "Bạn không có quyền chỉnh sửa bộ câu hỏi này.",
  "Quiz draft not found": "Không tìm thấy bản nháp bộ câu hỏi",
  "No draft was found for this Quiz ID.":
    "Không tìm thấy bản nháp nào cho mã bộ câu hỏi này.",
  "Back to quiz authoring": "Quay lại soạn bộ câu hỏi",
  "Quiz could not be loaded": "Không thể tải bộ câu hỏi",
  "Loading failed": "Tải không thành công",
  "The quiz draft could not be loaded.": "Không thể tải bản nháp bộ câu hỏi.",
  "Quiz title": "Tiêu đề bộ câu hỏi",
  "Published versions": "Các phiên bản đã xuất bản",
  "Saving draft": "Đang lưu bản nháp",
  "Saving…": "Đang lưu…",
  "Retry save": "Thử lưu lại",
  Save: "Lưu",
  Saved: "Đã lưu",
  Publish: "Xuất bản",
  "Draft was not saved": "Bản nháp chưa được lưu",
  "Quiz was not published": "Bộ câu hỏi chưa được xuất bản",
  "Publish complete": "Đã xuất bản",
  "Editor pane": "Ngăn trình soạn",
  Editor: "Trình soạn",
  Preview: "Xem trước",
  "Quiz Markdown editor": "Trình soạn Quiz Markdown",
  "Live quiz preview": "Xem trước bộ câu hỏi trực tiếp",
  // Publish dialog
  "Publish immutable QuizVersion": "Xuất bản QuizVersion bất biến",
  "This publishes quiz content only. Assessment timing, audience, and classroom delivery remain a separate product flow.":
    "Thao tác này chỉ xuất bản nội dung bộ câu hỏi. Thời gian, đối tượng và cách tổ chức bài kiểm tra thuộc một quy trình riêng.",
  Description: "Mô tả",
  "Optional context saved with this quiz draft":
    "Bối cảnh tùy chọn được lưu cùng bản nháp này",
  Cancel: "Hủy",
  "Publishing quiz": "Đang xuất bản bộ câu hỏi",
  "Publish QuizVersion": "Xuất bản QuizVersion",
  // Teacher-enablement and workspace gate
  "Teacher access required": "Cần quyền giáo viên",
  "Register as teacher to author quizzes":
    "Đăng ký làm giáo viên để soạn bộ câu hỏi",
  "Your verified account can add Teacher access while retaining its Student role.":
    "Tài khoản đã xác minh của bạn có thể thêm quyền Giáo viên mà vẫn giữ vai trò Học sinh.",
  "Registering as teacher": "Đang đăng ký làm giáo viên",
  "Open the Teaching workspace": "Mở không gian Giảng dạy",
  "Teacher access is ready": "Quyền giáo viên đã sẵn sàng",
  "Your account now has Student and Teacher access. Open the Teaching workspace to continue.":
    "Tài khoản của bạn hiện có cả quyền Học sinh và Giáo viên. Hãy mở không gian Giảng dạy để tiếp tục.",
  "Quiz authoring belongs to your Teaching workspace. Switching workspace changes presentation only; your account roles remain unchanged.":
    "Soạn bộ câu hỏi thuộc không gian Giảng dạy. Việc chuyển không gian chỉ thay đổi hiển thị; vai trò tài khoản của bạn không đổi.",
  // Published version history dialog
  "Quizopia could not connect. Try again.":
    "Quizopia không thể kết nối. Hãy thử lại.",
  "Quizopia got an unexpected validation response.":
    "Quizopia nhận được phản hồi xác thực không mong đợi.",
  "Published versions are read-only. Previewing one never changes your draft.":
    "Các phiên bản đã xuất bản là chỉ đọc. Xem trước không bao giờ làm thay đổi bản nháp của bạn.",
  "Close published versions": "Đóng các phiên bản đã xuất bản",
  "Loading published versions": "Đang tải các phiên bản đã xuất bản",
  "Loading versions...": "Đang tải các phiên bản...",
  "Version history could not be loaded": "Không thể tải lịch sử phiên bản",
  "Retry history": "Thử lại lịch sử",
  "No published versions yet": "Chưa có phiên bản nào được xuất bản",
  "Publish the current draft to create its first version.":
    "Xuất bản bản nháp hiện tại để tạo phiên bản đầu tiên.",
  "Published version history": "Lịch sử phiên bản đã xuất bản",
  "Version {number}": "Phiên bản {number}",
  "Schema {version}": "Lược đồ {version}",
  "More versions could not be loaded": "Không thể tải thêm phiên bản",
  "Loading more versions": "Đang tải thêm phiên bản",
  "Load more versions": "Tải thêm phiên bản",
  "All published versions loaded.": "Đã tải toàn bộ phiên bản đã xuất bản.",
  "Select a published version": "Chọn một phiên bản đã xuất bản",
  "A version's content loads when you open it.":
    "Nội dung phiên bản được tải khi bạn mở nó.",
  "Loading version {number}": "Đang tải phiên bản {number}",
  "Loading version {number}...": "Đang tải phiên bản {number}...",
  "Version {number} could not be loaded": "Không thể tải phiên bản {number}",
  "Retry version": "Thử lại phiên bản",
  "Create assessment from version {number}":
    "Tạo bài kiểm tra từ phiên bản {number}",
  "Read-only preview of this published version.":
    "Bản xem trước chỉ đọc của phiên bản đã xuất bản này.",
  "Published preview · Version {number}":
    "Xem trước đã xuất bản · Phiên bản {number}",
  // Live preview and diagnostics presentation
  "Live preview": "Xem trước trực tiếp",
  "Publish validation": "Xác thực xuất bản",
  "Editor diagnostics": "Chẩn đoán trình soạn",
  "Start a question with": "Bắt đầu một câu hỏi bằng",
  "to see the quiz preview.": "để xem bản xem trước bộ câu hỏi.",
  "Jump to source for question {number}, line {line}":
    "Tới nguồn của câu hỏi {number}, dòng {line}",
  "Options for question {number}": "Các phương án của câu hỏi {number}",
  "Marked correct": "Được đánh dấu đúng",
  "Not marked correct": "Chưa đánh dấu đúng",
  // Markdown editor surfaces
  "Quiz Markdown source": "Mã nguồn Quiz Markdown",
  "Quiz Markdown suggestions": "Gợi ý Quiz Markdown",
  "{count} Quiz Markdown suggestions available.":
    "Có {count} gợi ý Quiz Markdown.",
  "Autocomplete works at column 1, outside fenced code blocks. Publishing always re-validates.":
    "Gợi ý hoàn thành hoạt động tại cột 1, ngoài các khối mã. Khi xuất bản luôn kiểm tra lại.",
} as const;
