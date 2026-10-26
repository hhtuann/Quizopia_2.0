// Student attempt/join/result/review catalog plus realtime status chip copy.
// Keys are the exact rendered English source text; values are Vietnamese
// presentation only. Question stems/options, teacher-authored Markdown,
// canonical enum tokens (ACTIVE/SUBMITTED/ABANDONED, window states, question
// types) and API identifiers never pass through this lookup.
export const studentMessages = {
  // Workspace gate and request service availability
  // ("Student access required" lives in the assessment catalog.)
  "An authenticated account with current Student authority is required. The frontend does not verify admission or class membership itself.":
    "Yêu cầu tài khoản đã xác thực cùng quyền Học sinh hiện hành. Giao diện không tự xác minh quyền vào bài hay tư cách thành viên lớp.",
  "Open the Learning workspace": "Mở không gian Học tập",
  "Switch workspace to organize the Student experience. The Gateway and Assessment Service still authorize every operation.":
    "Chuyển không gian làm việc để sắp xếp trải nghiệm Học sinh. Gateway và dịch vụ Assessment vẫn thẩm định mọi thao tác.",
  "Assessment access unavailable": "Không thể truy cập bài kiểm tra",
  "The authenticated request service is unavailable. Sign in again before continuing.":
    "Dịch vụ yêu cầu đã xác thực chưa khả dụng. Hãy đăng nhập lại trước khi tiếp tục.",
  // Authorized publication preview ("Audience", "Attempt duration",
  // "{minutes} minutes", "Monitoring" and "Finalized" live in the assessment
  // catalog; "Revision {revision}" and "Publication ID" also live there.)
  Availability: "Tình trạng mở bài",
  "Unlimited; server closure rules still apply":
    "Không giới hạn; quy tắc chốt bài của máy chủ vẫn áp dụng",
  Required: "Bắt buộc",
  "Not required": "Không bắt buộc",
  "Authorized preview": "Xem trước được cấp quyền",
  Starts: "Bắt đầu",
  "Entry window ends": "Kết thúc thời gian vào bài",
  // Join screen
  "Public assessments still require a signed-in Student. Class access is checked against current authoritative membership.":
    "Bài kiểm tra công khai vẫn yêu cầu Học sinh đã đăng nhập. Quyền vào bài theo lớp được kiểm tra dựa trên tư cách thành viên hiện hành từ hệ thống.",
  "Entry method": "Cách vào bài",
  "Private locator": "Locator riêng tư",
  "Known class Publication": "Bài kiểm tra của lớp đã biết",
  "The locator stays in temporary application memory and is sent only in the authorized request body.":
    "Locator chỉ nằm trong bộ nhớ tạm của ứng dụng và chỉ được gửi trong phần thân yêu cầu đã cấp quyền.",
  "43-character private locator": "Locator riêng tư 43 ký tự",
  "Class browsing is not available. Ask your instructor for the canonical Publication ID.":
    "Không có tính năng duyệt bài theo lớp. Hãy hỏi giáo viên để lấy mã bài kiểm tra chuẩn.",
  "Checking assessment": "Đang kiểm tra bài kiểm tra",
  "Check access": "Kiểm tra quyền truy cập",
  "Starting is not currently available": "Hiện chưa thể bắt đầu",
  "Server-provided availability is {status}. A browser clock does not grant entry.":
    "Tình trạng do máy chủ cung cấp là {status}. Đồng hồ trình duyệt không tạo quyền vào bài.",
  "Starting attempt": "Đang bắt đầu bài làm",
  "Start or resume": "Bắt đầu hoặc tiếp tục",
  "Retry same start request": "Thử lại đúng yêu cầu bắt đầu",
  "Start outcome is uncertain": "Kết quả bắt đầu chưa xác định",
  "The original body and Idempotency-Key are retained in memory. Retry the same request; do not create a new start intent.":
    "Phần thân yêu cầu gốc và Idempotency-Key vẫn được giữ trong bộ nhớ. Hãy thử lại đúng yêu cầu đó; không tạo yêu cầu bắt đầu mới.",
  "This start outcome is unresolved and its exact recovery key exists only in memory. Leave anyway?":
    "Kết quả bắt đầu này chưa được giải quyết và khóa khôi phục chính xác chỉ tồn tại trong bộ nhớ. Vẫn rời trang?",
  "Resolve the uncertain start with its retained exact request before checking another assessment.":
    "Hãy giải quyết lần bắt đầu chưa xác định bằng đúng yêu cầu đang giữ trước khi kiểm tra bài khác.",
  "Enter the exact 43-character private locator.":
    "Nhập chính xác locator riêng tư gồm 43 ký tự.",
  "Enter a canonical lowercase Publication UUID.":
    "Nhập UUID bài kiểm tra chuẩn viết thường.",
  // Attempt loading and identification
  "Loading authoritative attempt": "Đang tải bài làm chính thức",
  "Attempt unavailable": "Không thể truy cập bài làm",
  "The Attempt ID is invalid.": "Mã bài làm không hợp lệ.",
  // Integrated monitoring composition on the attempt screen
  "Checking monitoring requirement": "Đang kiểm tra yêu cầu giám sát",
  "Assessment work is available while the authorized CLASS projection is checked. No activity or camera collection has started.":
    "Việc làm bài vẫn khả dụng trong khi phân lớp CLASS được cấp quyền đang được kiểm tra. Chưa bắt đầu thu thập hoạt động hay camera.",
  "Monitoring authority unavailable": "Không thể xác minh quyền giám sát",
  "The current monitoring requirement could not be verified, so activity and camera collection remain off. Assessment saves and submission stay independent.":
    "Không xác minh được yêu cầu giám sát hiện tại nên phần thu thập hoạt động và camera vẫn tắt. Lưu và nộp bài vẫn độc lập.",
  "Activity monitoring active": "Giám sát hoạt động đang bật",
  "Fresh server authorization confirmed this bounded CLASS Attempt. Browser evidence contains interaction metadata only and is not proof of misconduct. Assessment SAVE and SUBMIT do not wait for Proctoring.":
    "Quyền mới từ máy chủ đã xác nhận bài làm CLASS có giới hạn này. Dữ liệu từ trình duyệt chỉ chứa siêu dữ liệu tương tác và không phải bằng chứng vi phạm. LƯU và NỘP bài không chờ Proctoring.",
  "Monitoring unavailable": "Giám sát chưa khả dụng",
  "No activity observer or camera is active. This does not change answers, grading, or deadlines.":
    "Không có trình quan sát hoạt động hay camera nào đang bật. Điều này không thay đổi câu trả lời, điểm số hay thời hạn.",
  "Retry monitoring verification": "Thử lại việc xác minh giám sát",
  "Starting activity monitoring": "Đang bật giám sát hoạt động",
  "Current backend authority is being verified before any browser observer or camera control becomes active.":
    "Quyền từ hệ thống phía sau đang được xác minh trước khi bất kỳ trình quan sát trình duyệt hay điều khiển camera nào hoạt động.",
  // Informative countdown and header
  "Informative countdown": "Đồng hồ đếm ngược tham khảo",
  "No individual deadline": "Không có thời hạn riêng",
  "Server time and refreshed status are authoritative.":
    "Thời gian máy chủ và trạng thái được làm mới là chính thức.",
  "Attempt {ordinal}": "Lượt làm bài {ordinal}",
  "Student assessment": "Bài kiểm tra của học sinh",
  "Attempt {attemptId}": "Bài làm {attemptId}",
  "{answered} of {total} answered": "Đã trả lời {answered} trên {total}",
  "Deadline {deadline}": "Hạn chót {deadline}",
  // Focused attempt workspace chrome
  "Back to app": "Quay lại ứng dụng",
  Answered: "Đã trả lời",
  // Closure cutoff
  "Teacher closure cutoff is active": "Đang trong thời hạn chốt của giáo viên",
  "The authoritative cutoff is {cutoff}. Only committed answers are included if the server finalizes the attempt.":
    "Thời hạn chính thức là {cutoff}. Chỉ những câu trả lời đã lưu mới được tính nếu máy chủ kết thúc bài làm.",
  // Authority pause, countdown expiry, terminal errors
  "Checking authoritative status": "Đang kiểm tra trạng thái chính thức",
  "The local countdown reached zero. Editing is paused while the Attempt is reconciled with the server.":
    "Đồng hồ đếm ngược cục bộ đã về không. Việc chỉnh sửa tạm dừng trong khi bài làm được đối chiếu với máy chủ.",
  "Retry status check": "Thử lại kiểm tra trạng thái",
  "Action could not be confirmed": "Không thể xác nhận thao tác",
  "Authoritative reconciliation required":
    "Cần đối chiếu trạng thái chính thức",
  "Answer and terminal actions remain paused until current server state is confirmed.":
    "Các thao tác trả lời và kết thúc bài vẫn tạm dừng cho đến khi trạng thái hiện tại của máy chủ được xác nhận.",
  "The browser is offline. Actions are paused until authoritative Attempt status is refreshed.":
    "Trình duyệt đang ngoại tuyến. Các thao tác tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "{action} outcome is uncertain": "Kết quả thao tác {action} chưa xác định",
  "The original body and Idempotency-Key remain in memory.":
    "Phần thân yêu cầu gốc và Idempotency-Key vẫn được giữ trong bộ nhớ.",
  "Retry exact operation": "Thử lại đúng thao tác",
  "Check authoritative state": "Kiểm tra trạng thái chính thức",
  "Actions are paused until authoritative Attempt status is refreshed.":
    "Các thao tác tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "Submission is paused until authoritative Attempt status is refreshed.":
    "Việc nộp bài tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "Abandon is paused until authoritative Attempt status is refreshed.":
    "Việc bỏ bài tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "Retake is paused until authoritative Attempt status is refreshed.":
    "Việc làm lại tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "Resolve every failed, conflicting, or uncertain save before submission.":
    "Hãy xử lý mọi lần lưu thất bại, xung đột hoặc chưa xác định trước khi nộp bài.",
  "Resolve every pending save before abandoning so its outcome is explicit.":
    "Hãy xử lý mọi lần lưu đang chờ trước khi bỏ bài để kết quả được rõ ràng.",
  "One or more answers do not match the required schema.":
    "Một hoặc nhiều câu trả lời không đúng định dạng yêu cầu.",
  "The complete answer set exceeds the server body limit. Nothing was truncated or submitted.":
    "Tập câu trả lời đầy đủ vượt quá giới hạn của máy chủ. Không có gì bị cắt bớt hay nộp đi.",
  "The Attempt changed on the server. Your complete local answer set is preserved; resolve any highlighted conflicts, then confirm the action again.":
    "Bài làm đã thay đổi trên máy chủ. Toàn bộ câu trả lời cục bộ được giữ nguyên; hãy xử lý các xung đột được đánh dấu rồi xác nhận lại thao tác.",
  "The Attempt revision changed, and authoritative refresh failed. {message}":
    "Phiên bản bài làm đã thay đổi và việc làm mới trạng thái chính thức thất bại. {message}",
  "Authoritative status could not be refreshed. Actions remain paused. {message}":
    "Không thể làm mới trạng thái chính thức. Các thao tác vẫn tạm dừng. {message}",
  "A retake cannot be discovered from the old Attempt. Retry the retained exact operation to resolve its outcome.":
    "Không thể phát hiện lượt làm lại từ bài làm cũ. Hãy thử lại đúng thao tác đang giữ để xác định kết quả.",
  "Authoritative state is still ACTIVE. Retry the retained operation to resolve its outcome.":
    "Trạng thái chính thức vẫn là ACTIVE. Hãy thử lại đúng thao tác đang giữ để xác định kết quả.",
  "This mutation outcome is unresolved and its exact recovery key exists only in memory. Leave anyway?":
    "Kết quả thao tác này chưa được giải quyết và khóa khôi phục chính xác chỉ tồn tại trong bộ nhớ. Vẫn rời trang?",
  // Terminal attempt panel
  "Attempt submitted": "Đã nộp bài",
  "Attempt abandoned": "Đã bỏ bài",
  "This terminal state was read from the server. Answers can no longer be changed.":
    "Trạng thái kết thúc này được đọc từ máy chủ. Không thể thay đổi câu trả lời nữa.",
  "View result": "Xem kết quả",
  "Start explicit retake": "Bắt đầu lượt làm lại",
  "Enter another assessment": "Vào bài kiểm tra khác",
  "If this is a PUBLIC assessment and the page was reloaded, enter its private locator again before retaking. The locator is never persisted.":
    "Nếu đây là bài kiểm tra công khai và trang đã được tải lại, hãy nhập lại locator riêng tư trước khi làm lại. Locator không bao giờ được lưu.",
  // Question navigation
  "Attempt questions": "Các câu hỏi của bài làm",
  Questions: "Câu hỏi",
  "{start}–{end} of {total}": "{start}–{end} trên {total}",
  "Question {number}, {status}": "Câu hỏi {number}, {status}",
  "Previous questions": "Câu hỏi trước",
  "Next questions": "Câu hỏi sau",
  // Question panel and per-question save actions
  "Question {number} · {type}": "Câu hỏi {number} · {type}",
  "Retry exact save": "Thử lại đúng lần lưu",
  "Check server": "Kiểm tra máy chủ",
  "Save this draft": "Lưu bản nháp này",
  // Submission bar and confirmation dialogs
  "Submission sends the complete answer collection and never truncates it. Abandon is distinct, irreversible, and creates no result.":
    "Việc nộp bài gửi toàn bộ tập câu trả lời và không bao giờ cắt bớt. Bỏ bài là thao tác riêng, không thể hoàn tác và không tạo kết quả.",
  "Submit attempt": "Nộp bài",
  "Abandon attempt": "Bỏ bài",
  "Submit this Attempt?": "Nộp bài làm này?",
  "Submit complete answers": "Nộp toàn bộ câu trả lời",
  "Quizopia will save pending drafts, then submit exactly one answer for every question. The server computes and persists the grade atomically.":
    "Quizopia sẽ lưu các bản nháp đang chờ, sau đó nộp đúng một câu trả lời cho mỗi câu hỏi. Máy chủ tính và lưu điểm một cách nguyên tử.",
  "Abandon this Attempt?": "Bỏ bài làm này?",
  "Abandon Attempt": "Bỏ bài làm",
  "Abandoning is irreversible, consumes this Attempt entitlement, and creates no result. Closing the browser never performs this action.":
    "Bỏ bài không thể hoàn tác, tiêu tốn lượt làm này và không tạo kết quả. Đóng trình duyệt không bao giờ thực hiện hành động này.",
  // Result screen
  "Result unavailable": "Không thể xem kết quả",
  "Server-issued result": "Kết quả do máy chủ cấp",
  "Attempt result": "Kết quả bài làm",
  "Total score": "Tổng điểm",
  "{earned} of {possible} exact weight":
    "{earned} trên {possible} trọng số chính xác",
  Attempt: "Lượt làm",
  "Terminal reason": "Lý do kết thúc",
  "Grading policy": "Chính sách chấm điểm",
  "Best successfully graded Attempt": "Lượt làm đã chấm có điểm cao nhất",
  "No aggregate score is available.": "Chưa có điểm tổng hợp.",
  "Attempt {ordinal}: {percentage}% ({earned}/{possible}).":
    "Lượt làm {ordinal}: {percentage}% ({earned}/{possible}).",
  "Answer details remain protected": "Chi tiết câu trả lời vẫn được bảo vệ",
  "Correct answers and explanations are available only through the separate, server-authorized review route after durable Publication closure.":
    "Đáp án và lời giải chỉ khả dụng qua trang xem lại riêng do máy chủ cấp quyền, sau khi bài kiểm tra được đóng vĩnh viễn.",
  "Request answer review": "Yêu cầu xem lại câu trả lời",
  "Back to Attempt": "Quay lại bài làm",
  // Review screen
  "Answer review unavailable": "Chưa thể xem lại câu trả lời",
  "{message} No correct-answer request was made before opening this explicit review page.":
    "{message} Không có yêu cầu đáp án nào được gửi trước khi mở trang xem lại này.",
  "Back to result": "Quay lại kết quả",
  "Authorized closed review": "Xem lại sau khi đã đóng bài",
  "Answer review": "Xem lại câu trả lời",
  "The server released these details for this submitted owner after durable closure.":
    "Máy chủ đã cấp những chi tiết này cho chủ sở hữu đã nộp bài sau khi bài kiểm tra được đóng vĩnh viễn.",
  "Question {number} · {earned}/{possible}":
    "Câu hỏi {number} · {earned}/{possible}",
  "Your answer": "Câu trả lời của bạn",
  "Correct answer": "Đáp án đúng",
  Explanation: "Lời giải",
  // Answer fields
  "Choose one answer": "Chọn một đáp án",
  "Clear answer": "Xóa câu trả lời",
  "Select every answer that applies.": "Chọn mọi đáp án đúng.",
  "Judge every statement. Unanswered is distinct from false.":
    "Hãy đánh giá mọi nhận định. Chưa trả lời khác với Sai.",
  True: "Đúng",
  False: "Sai",
  Unanswered: "Chưa trả lời",
  "Four-character numeric answer": "Câu trả lời số gồm bốn ký tự",
  "Examples: 2.50, 1234, -2.5, -.25. Blank means unanswered. No exponent, comma, plus sign, padding, or rounding is applied.":
    "Ví dụ: 2.50, 1234, -2.5, -.25. Để trống nghĩa là chưa trả lời. Không áp dụng ký hiệu mũ, dấu phẩy, dấu cộng, thêm số 0 hay làm tròn.",
  "The answer type does not match this question. Refresh authoritative state.":
    "Kiểu câu trả lời không khớp với câu hỏi này. Hãy làm mới trạng thái chính thức.",
  "Unsaved draft": "Bản nháp chưa lưu",
  "Save failed": "Lưu thất bại",
  "Revision conflict": "Xung đột phiên bản",
  "Save outcome uncertain": "Kết quả lưu chưa xác định",
  // Autosave coordinator status copy (localize targets)
  "Authoritative state changed. Your local answer is preserved; save it deliberately before retrying the terminal action.":
    "Trạng thái chính thức đã thay đổi. Câu trả lời cục bộ của bạn vẫn được giữ; hãy chủ động lưu lại trước khi thử lại thao tác kết thúc.",
  "Autosave is paused until authoritative Attempt status is refreshed. Your draft remains in memory.":
    "Tự động lưu tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới. Bản nháp vẫn nằm trong bộ nhớ.",
  "The server revision changed, and refresh failed. {message}":
    "Phiên bản trên máy chủ đã thay đổi và việc làm mới thất bại. {message}",
  "Server state was refreshed. Your local edit is preserved; choose Save this draft to apply it against the new revision.":
    "Trạng thái máy chủ đã được làm mới. Chỉnh sửa cục bộ vẫn được giữ; hãy chọn Lưu bản nháp này để áp dụng lên phiên bản mới.",
  "The attempt became terminal. Your local edit was preserved locally but cannot be saved.":
    "Bài làm đã chuyển sang trạng thái kết thúc. Chỉnh sửa cục bộ vẫn được giữ trong máy nhưng không thể lưu.",
  "Exact retry is paused until authoritative Attempt status is refreshed.":
    "Việc thử lại chính xác tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "Saving is paused until authoritative Attempt status is refreshed.":
    "Việc lưu tạm dừng cho đến khi trạng thái bài làm chính thức được làm mới.",
  "The save outcome is unknown. Retry the exact save or check authoritative state before sending another version.":
    "Kết quả lần lưu chưa rõ ràng. Hãy thử lại đúng lần lưu hoặc kiểm tra trạng thái chính thức trước khi gửi phiên bản khác.",
  "Enter exactly four numeric characters, such as 2.50, 1234, -2.5, or -.25; blank means unanswered.":
    "Nhập đúng bốn ký tự số, ví dụ 2.50, 1234, -2.5 hoặc -.25; để trống nghĩa là chưa trả lời.",
  "Resolve the uncertain save before another answer can be sent.":
    "Hãy giải quyết lần lưu chưa xác định trước khi gửi câu trả lời khác.",
  "Your newer draft is preserved. Resolve the prior unknown save before sending this version.":
    "Bản nháp mới hơn của bạn vẫn được giữ. Hãy giải quyết lần lưu chưa rõ trước đó trước khi gửi phiên bản này.",
  "Authoritative state does not confirm this save. Your local draft remains available; choose Save this draft to create a new operation.":
    "Trạng thái chính thức không xác nhận lần lưu này. Bản nháp cục bộ vẫn sẵn có; hãy chọn Lưu bản nháp này để tạo thao tác mới.",
  // Unsaved navigation guards
  "This Attempt has unsaved or unresolved answers. Leave this page anyway?":
    "Bài làm này có câu trả lời chưa lưu hoặc chưa được giải quyết. Vẫn rời trang này?",
  // Attempt failure presentation (attemptFailureMessage localize targets)
  "Your session changed or ended. Sign in again before continuing.":
    "Phiên đăng nhập của bạn đã thay đổi hoặc kết thúc. Hãy đăng nhập lại trước khi tiếp tục.",
  "The request was cancelled. Its outcome may be uncertain.":
    "Yêu cầu đã bị hủy. Kết quả của nó có thể chưa rõ ràng.",
  "The service is temporarily unavailable. Your account has not been logged out.":
    "Dịch vụ tạm thời không khả dụng. Tài khoản của bạn không bị đăng xuất.",
  "The request could not be sent because its input is invalid.":
    "Không thể gửi yêu cầu vì dữ liệu đầu vào không hợp lệ.",
  "The server response could not be verified. Refresh authoritative state before continuing.":
    "Không thể xác minh phản hồi của máy chủ. Hãy làm mới trạng thái chính thức trước khi tiếp tục.",
  "Student access is required for this assessment.":
    "Bài kiểm tra này yêu cầu quyền Học sinh.",
  "This assessment is unavailable or you do not have permission to access it.":
    "Bài kiểm tra này không khả dụng hoặc bạn không có quyền truy cập.",
  "This attempt is unavailable or does not belong to the current account.":
    "Bài làm này không khả dụng hoặc không thuộc tài khoản hiện tại.",
  "Current class membership could not authorize this assessment.":
    "Tư cách thành viên lớp hiện tại không đủ để cấp quyền cho bài kiểm tra này.",
  "This assessment is not available yet.": "Bài kiểm tra này chưa mở.",
  "The window for starting this assessment has ended.":
    "Thời gian bắt đầu bài kiểm tra này đã kết thúc.",
  "No more attempts are available for this assessment.":
    "Không còn lượt làm nào cho bài kiểm tra này.",
  "An active attempt already exists. Resume it instead.":
    "Đã có một bài làm đang diễn ra. Hãy tiếp tục bài làm đó.",
  "A retake is not currently available.": "Hiện chưa thể làm lại.",
  "This attempt changed on the server. Refresh and reconcile your unsaved answer.":
    "Bài làm đã thay đổi trên máy chủ. Hãy làm mới và đối chiếu câu trả lời chưa lưu.",
  "This operation identity was already used for different content.":
    "Danh tính thao tác này đã được dùng cho nội dung khác.",
  "This attempt has already reached a terminal state.":
    "Bài làm này đã ở trạng thái kết thúc.",
  "The authoritative attempt deadline has been reached.":
    "Đã đến hạn chót chính thức của bài làm.",
  "The authoritative closure cutoff has been reached.":
    "Đã đến thời hạn chốt chính thức.",
  "A closure workflow is pending, so a new Attempt cannot be created.":
    "Quy trình chốt bài đang chờ xử lý nên không thể tạo bài làm mới.",
  "A result is not available for this attempt.":
    "Chưa có kết quả cho bài làm này.",
  "Answer review is unavailable until the assessment is durably closed.":
    "Chưa thể xem lại câu trả lời cho đến khi bài kiểm tra được đóng vĩnh viễn.",
  "The complete request exceeds the accepted server limit. Nothing was truncated or submitted.":
    "Yêu cầu đầy đủ vượt quá giới hạn cho phép của máy chủ. Không có gì bị cắt bớt hay nộp đi.",
  "Too many requests were received. Wait, then retry the same operation.":
    "Có quá nhiều yêu cầu. Hãy đợi rồi thử lại đúng thao tác.",
  "A required authority or assessment dependency is unavailable. Your account has not been logged out.":
    "Một quyền hoặc dịch vụ phụ thuộc của Assessment không khả dụng. Tài khoản của bạn không bị đăng xuất.",
  "Your session could not authorize this request. Sign in again.":
    "Phiên đăng nhập không đủ quyền cho yêu cầu này. Hãy đăng nhập lại.",
  "The server could not complete the request (HTTP {status}).":
    "Máy chủ không thể hoàn tất yêu cầu (HTTP {status}).",
  // Realtime status chip (attempt header)
  "Live updates: {state}": "Cập nhật trực tiếp: {state}",
  "Live updates: {state} (attempt {count})":
    "Cập nhật trực tiếp: {state} (lần thử {count})",
  "connecting…": "đang kết nối…",
  "signing in…": "đang đăng nhập…",
  connected: "đã kết nối",
  "reconnecting…": "đang kết nối lại…",
  unavailable: "không khả dụng",
  off: "tắt",
  "Live update hints are best-effort; server state stays authoritative.":
    "Gợi ý cập nhật trực tiếp mang tính cố gắng tối đa; trạng thái máy chủ vẫn là chính thức.",
} as const;
