// Student attempt/join/result/review catalog plus realtime status chip copy.
// Keys are the exact rendered English source text; values are Vietnamese
// presentation only. Question stems/options, teacher-authored Markdown,
// canonical enum tokens (ACTIVE/SUBMITTED/ABANDONED, window states, question
// types) and API identifiers never pass through this lookup.
export const studentMessages = {
  // Workspace gate and request service availability
  // ("Student access required" lives in the assessment catalog.)
  "Please sign in with a Student account to continue.":
    "Hãy đăng nhập bằng tài khoản Học sinh để tiếp tục.",
  "Open the Learning workspace": "Mở không gian Học tập",
  "Switch workspace to organize your learning.":
    "Chuyển không gian làm việc để sắp xếp việc học của bạn.",
  "Assessment access unavailable": "Không thể truy cập bài kiểm tra",
  // Authorized publication preview ("Audience", "Attempt duration",
  // "{minutes} minutes", "Monitoring" and "Finalized" live in the assessment
  // catalog; "Revision {revision}" and "Publication ID" also live there.)
  Availability: "Tình trạng mở bài",
  "Unlimited; closing rules still apply":
    "Không giới hạn; vẫn áp dụng quy tắc chốt bài",
  Required: "Bắt buộc",
  "Not required": "Không bắt buộc",
  "Authorized preview": "Xem trước được cấp quyền",
  Starts: "Bắt đầu",
  "Entry window ends": "Kết thúc thời gian vào bài",
  // Join screen
  "Public assessments still require a signed-in Student.":
    "Bài kiểm tra công khai vẫn yêu cầu Học sinh đã đăng nhập.",
  "Entry method": "Cách vào bài",
  "Private locator": "Locator riêng tư",
  "Known class Publication": "Bài kiểm tra của lớp đã biết",
  "The locator is kept in memory only and is never saved.":
    "Locator chỉ được giữ trong bộ nhớ và không bao giờ được lưu.",
  "43-character private locator": "Locator riêng tư 43 ký tự",
  "Ask your instructor for the Publication ID.":
    "Hãy hỏi giáo viên để lấy mã bài kiểm tra.",
  "Checking assessment": "Đang kiểm tra bài kiểm tra",
  "Check access": "Kiểm tra quyền truy cập",
  "Starting is not currently available": "Hiện chưa thể bắt đầu",
  "Availability is {status}, checked by Quizopia — not your device clock.":
    "Tình trạng là {status}, do Quizopia kiểm tra — không theo đồng hồ thiết bị của bạn.",
  "Starting attempt": "Đang bắt đầu bài làm",
  "Start or resume": "Bắt đầu hoặc tiếp tục",
  "Retry same start request": "Thử lại đúng yêu cầu bắt đầu",
  "Start outcome is uncertain": "Kết quả bắt đầu chưa xác định",
  "Your original request is kept in memory. Retry the same request; do not start a new one.":
    "Yêu cầu gốc vẫn được giữ trong bộ nhớ. Hãy thử lại đúng yêu cầu đó; không bắt đầu yêu cầu mới.",
  "This start action may not have finished. Leave anyway?":
    "Thao tác bắt đầu có thể chưa xong. Vẫn rời trang?",
  "Retry the unfinished start before checking another assessment.":
    "Hãy xử lý lần bắt đầu chưa xong trước khi kiểm tra bài khác.",
  "Enter the exact 43-character private locator.":
    "Nhập chính xác locator riêng tư gồm 43 ký tự.",
  "Enter the Publication ID exactly as given (lowercase).":
    "Nhập chính xác mã bài kiểm tra được cấp (chữ thường).",
  // Attempt loading and identification
  "Loading your attempt": "Đang tải bài làm",
  "Attempt unavailable": "Không thể truy cập bài làm",
  "The Attempt ID is invalid.": "Mã bài làm không hợp lệ.",
  // Integrated monitoring composition on the attempt screen
  "Checking monitoring requirement": "Đang kiểm tra yêu cầu giám sát",
  "You can start working while monitoring is being checked. Nothing is being recorded yet.":
    "Bạn có thể bắt đầu làm bài trong khi kiểm tra giám sát. Chưa ghi hoạt động hay hình ảnh nào.",
  "Monitoring requirement unavailable": "Không thể xác minh yêu cầu giám sát",
  "Monitoring could not be verified, so recording stays off. Saving and submitting are not affected.":
    "Chưa xác minh được yêu cầu giám sát nên không ghi hình. Lưu và nộp bài không bị ảnh hưởng.",
  "Activity monitoring active": "Giám sát hoạt động đang bật",
  "Monitoring is on for this class attempt. Browser signals help with review and are never proof of cheating. Saving and submitting never wait for monitoring.":
    "Giám sát đang bật cho bài làm lớp này. Tín hiệu từ trình duyệt chỉ phục vụ xem xét, không bao giờ là bằng chứng gian lận. Lưu và nộp bài không chờ giám sát.",
  "Monitoring unavailable": "Giám sát chưa khả dụng",
  "No activity observer or camera is active. This does not change answers, grading, or deadlines.":
    "Không có trình quan sát hoạt động hay camera nào đang bật. Điều này không thay đổi câu trả lời, điểm số hay thời hạn.",
  "Retry monitoring verification": "Thử lại việc xác minh giám sát",
  "Starting activity monitoring": "Đang bật giám sát hoạt động",
  "Checking permission before any monitoring starts.":
    "Đang kiểm tra quyền trước khi bật giám sát.",
  // Informative countdown and header
  "Informative countdown": "Đồng hồ đếm ngược tham khảo",
  "No individual deadline": "Không có thời hạn riêng",
  "Deadlines follow Quizopia's clock, not your device.":
    "Thời hạn theo đồng hồ của Quizopia, không theo thiết bị của bạn.",
  "Attempt {ordinal}": "Lượt làm bài {ordinal}",
  "Student assessment": "Bài kiểm tra của học sinh",
  "{answered} of {total} answered": "Đã trả lời {answered} trên {total}",
  "Deadline {deadline}": "Hạn chót {deadline}",
  // Focused attempt workspace chrome
  "Back to app": "Quay lại ứng dụng",
  Answered: "Đã trả lời",
  // Closure cutoff
  "Teacher closure cutoff is active": "Đang trong thời hạn chốt của giáo viên",
  "The cutoff is {cutoff}. Only saved answers count if the attempt is finalized.":
    "Mốc chốt là {cutoff}. Chỉ những câu trả lời đã lưu mới được tính nếu bài làm bị chốt.",
  // Authority pause, countdown expiry, terminal errors
  "Checking status": "Đang kiểm tra trạng thái",
  "Time is up. Editing is paused while the final state is confirmed.":
    "Đã hết thời gian. Tạm dừng chỉnh sửa trong khi xác nhận trạng thái cuối.",
  "Retry status check": "Thử lại kiểm tra trạng thái",
  "Action could not be confirmed": "Không thể xác nhận thao tác",
  "Status check required": "Cần kiểm tra trạng thái",
  "Answers and final actions stay paused until the current state is confirmed.":
    "Các thao tác trả lời và kết thúc bài vẫn tạm dừng cho đến khi xác nhận trạng thái hiện tại.",
  "You are offline. Actions are paused until the attempt status is refreshed.":
    "Bạn đang ngoại tuyến. Các thao tác tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "{action} outcome is uncertain": "Kết quả thao tác {action} chưa xác định",
  "Your original request is kept in memory.":
    "Yêu cầu gốc vẫn được giữ trong bộ nhớ.",
  "Retry exact operation": "Thử lại đúng thao tác",
  "Check current state": "Kiểm tra trạng thái hiện tại",
  "Actions are paused until the attempt status is refreshed.":
    "Các thao tác tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "Submitting is paused until the attempt status is refreshed.":
    "Nộp bài tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "Abandoning is paused until the attempt status is refreshed.":
    "Bỏ bài tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "Retaking is paused until the attempt status is refreshed.":
    "Làm lại tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "Resolve every failed, conflicting, or pending save before submission.":
    "Hãy xử lý mọi lần lưu thất bại, xung đột hoặc đang chờ trước khi nộp bài.",
  "Resolve every pending save before abandoning so its outcome is explicit.":
    "Hãy xử lý mọi lần lưu đang chờ trước khi bỏ bài để kết quả được rõ ràng.",
  "One or more answers do not match the required schema.":
    "Một hoặc nhiều câu trả lời không đúng định dạng yêu cầu.",
  "Your answers are too large to submit. Nothing was cut or sent.":
    "Tập câu trả lời quá lớn để nộp. Không có gì bị cắt bớt hay gửi đi.",
  "This attempt changed elsewhere. Your answers are preserved; resolve the highlighted conflicts, then confirm the action again.":
    "Bài làm đã thay đổi. Các câu trả lời của bạn vẫn được giữ; hãy xử lý các xung đột được đánh dấu rồi xác nhận lại thao tác.",
  "The attempt changed and refreshing failed. {message}":
    "Bài làm đã thay đổi và không làm mới được. {message}",
  "Status could not be refreshed. Actions remain paused. {message}":
    "Không thể làm mới trạng thái. Các thao tác vẫn tạm dừng. {message}",
  "Retry the pending action first to start a retake.":
    "Hãy thử lại thao tác đang chờ trước khi làm lại.",
  "The attempt is still active. Retry the pending action to resolve it.":
    "Bài làm vẫn đang diễn ra. Hãy thử lại thao tác đang chờ.",
  "This action may not have finished. Leave anyway?":
    "Thao tác này có thể chưa xong. Vẫn rời trang?",
  // Terminal attempt panel
  "Attempt submitted": "Đã nộp bài",
  "Attempt abandoned": "Đã bỏ bài",
  "This attempt has ended. Answers can no longer be changed.":
    "Bài làm đã kết thúc. Không thể thay đổi câu trả lời nữa.",
  "View result": "Xem kết quả",
  "Start explicit retake": "Bắt đầu lượt làm lại",
  "Enter another assessment": "Vào bài kiểm tra khác",
  "For a PUBLIC assessment after a reload, enter its private locator again before retaking. The locator is never saved.":
    "Với bài kiểm tra PUBLIC sau khi tải lại trang, hãy nhập lại locator trước khi làm lại. Locator không bao giờ được lưu.",
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
  "Check status": "Kiểm tra trạng thái",
  "Save this draft": "Lưu bản nháp này",
  // Submission bar and confirmation dialogs
  "Submitting sends all your answers. Abandoning is irreversible and leaves no result.":
    "Nộp bài sẽ gửi toàn bộ câu trả lời của bạn. Bỏ bài không thể hoàn tác và không tạo kết quả.",
  "Submit attempt": "Nộp bài",
  "Abandon attempt": "Bỏ bài",
  "Submit this Attempt?": "Nộp bài làm này?",
  "Submit complete answers": "Nộp toàn bộ câu trả lời",
  "Quizopia will save pending drafts, then submit one answer per question. Your grade is calculated right after.":
    "Quizopia sẽ lưu các bản nháp đang chờ, sau đó nộp đúng một câu trả lời cho mỗi câu hỏi. Điểm được tính ngay sau đó.",
  "Abandon this Attempt?": "Bỏ bài làm này?",
  "Abandon Attempt": "Bỏ bài làm",
  "Abandoning is irreversible, uses up this attempt, and creates no result. Closing the browser never abandons for you.":
    "Bỏ bài không thể hoàn tác, tiêu tốn lượt làm này và không tạo kết quả. Đóng trình duyệt không tự bỏ bài.",
  // Result screen
  "Result unavailable": "Không thể xem kết quả",
  "Official result": "Kết quả chính thức",
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
  "Correct answers and explanations unlock in the review page after the assessment closes.":
    "Đáp án và lời giải chỉ mở trong trang xem lại sau khi bài kiểm tra được đóng.",
  "Request answer review": "Yêu cầu xem lại câu trả lời",
  "Back to Attempt": "Quay lại bài làm",
  // Review screen
  "Answer review unavailable": "Chưa thể xem lại câu trả lời",
  "{message} No answer review was requested before opening this page.":
    "{message} Chưa có yêu cầu xem đáp án nào trước khi mở trang này.",
  "Back to result": "Quay lại kết quả",
  "Authorized closed review": "Xem lại sau khi đã đóng bài",
  "Answer review": "Xem lại câu trả lời",
  "These details are available because you submitted and the assessment has closed.":
    "Những chi tiết này khả dụng vì bạn đã nộp bài và bài kiểm tra đã đóng.",
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
  "The answer type does not match this question. Refresh to continue.":
    "Kiểu câu trả lời không khớp câu hỏi này. Hãy làm mới để tiếp tục.",
  "Unsaved draft": "Bản nháp chưa lưu",
  "Save failed": "Lưu thất bại",
  "Revision conflict": "Xung đột phiên bản",
  "Save outcome uncertain": "Kết quả lưu chưa xác định",
  // Autosave coordinator status copy (localize targets)
  "The attempt changed. Your local answer is preserved; save it before retrying the final action.":
    "Bài làm đã thay đổi. Câu trả lời của bạn vẫn được giữ; hãy lưu lại trước khi thử lại thao tác kết thúc.",
  "Autosave is paused until the attempt status is refreshed. Your draft is safe.":
    "Tự động lưu tạm dừng cho đến khi làm mới trạng thái. Bản nháp của bạn vẫn an toàn.",
  "The latest state was loaded. Your edit is preserved; choose Save this draft to apply it.":
    "Đã tải trạng thái mới nhất. Chỉnh sửa của bạn vẫn được giữ; chọn Lưu bản nháp này để áp dụng.",
  "The attempt has ended. Your edit is kept on screen but cannot be saved.":
    "Bài làm đã kết thúc. Chỉnh sửa vẫn hiển thị nhưng không thể lưu.",
  "Retry is paused until the attempt status is refreshed.":
    "Thử lại tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "Saving is paused until the attempt status is refreshed.":
    "Lưu tạm dừng cho đến khi làm mới trạng thái bài làm.",
  "The save result is unknown. Retry the same save or check the current state before sending another version.":
    "Chưa rõ kết quả lần lưu. Hãy thử lại đúng lần lưu hoặc kiểm tra trạng thái hiện tại trước khi gửi phiên bản khác.",
  "Enter exactly four numeric characters, such as 2.50, 1234, -2.5, or -.25; blank means unanswered.":
    "Nhập đúng bốn ký tự số, ví dụ 2.50, 1234, -2.5 hoặc -.25; để trống nghĩa là chưa trả lời.",
  "Resolve the pending save before sending another answer.":
    "Hãy xử lý lần lưu đang chờ trước khi gửi câu trả lời khác.",
  "Your newer draft is safe. Resolve the previous pending save before sending this version.":
    "Bản nháp mới hơn của bạn vẫn an toàn. Hãy xử lý lần lưu đang chờ trước đó trước khi gửi phiên bản này.",
  "This save was not confirmed. Your draft is still here; choose Save this draft to try again.":
    "Lần lưu này chưa được xác nhận. Bản nháp vẫn còn; chọn Lưu bản nháp này để thử lại.",
  // Unsaved navigation guards
  "This attempt has unsaved or pending answers. Leave this page anyway?":
    "Bài làm này có câu trả lời chưa lưu hoặc đang chờ xử lý. Vẫn rời trang này?",
  // Attempt failure presentation (attemptFailureMessage localize targets)
  "Your session changed or ended. Sign in again before continuing.":
    "Phiên đăng nhập của bạn đã thay đổi hoặc kết thúc. Hãy đăng nhập lại trước khi tiếp tục.",
  "The request was cancelled. Its outcome may be uncertain.":
    "Yêu cầu đã bị hủy. Kết quả của nó có thể chưa rõ ràng.",
  "Quizopia is temporarily unavailable. You are still signed in.":
    "Quizopia tạm thời không khả dụng. Bạn vẫn còn đăng nhập.",
  "The request could not be sent because its input is invalid.":
    "Không thể gửi yêu cầu vì dữ liệu đầu vào không hợp lệ.",
  "The response could not be verified. Refresh before continuing.":
    "Không thể xác minh phản hồi. Hãy làm mới trước khi tiếp tục.",
  "Student access is required for this assessment.":
    "Bài kiểm tra này yêu cầu quyền Học sinh.",
  "This assessment is unavailable or you do not have permission to access it.":
    "Bài kiểm tra này không khả dụng hoặc bạn không có quyền truy cập.",
  "This attempt is unavailable or does not belong to the current account.":
    "Bài làm này không khả dụng hoặc không thuộc tài khoản hiện tại.",
  "Your current class membership does not grant access.":
    "Tư cách thành viên lớp hiện tại không đủ để vào bài.",
  "This assessment is not available yet.": "Bài kiểm tra này chưa mở.",
  "The window for starting this assessment has ended.":
    "Thời gian bắt đầu bài kiểm tra này đã kết thúc.",
  "No more attempts are available for this assessment.":
    "Không còn lượt làm nào cho bài kiểm tra này.",
  "An active attempt already exists. Resume it instead.":
    "Đã có một bài làm đang diễn ra. Hãy tiếp tục bài làm đó.",
  "A retake is not currently available.": "Hiện chưa thể làm lại.",
  "This attempt changed. Refresh to reconcile your unsaved answer.":
    "Bài làm đã thay đổi. Hãy làm mới để đối chiếu câu trả lời chưa lưu.",
  "This request was already used for different content.":
    "Yêu cầu này đã được dùng cho nội dung khác.",
  "This attempt has already reached a terminal state.":
    "Bài làm này đã ở trạng thái kết thúc.",
  "The attempt deadline has been reached.": "Đã đến hạn chót của bài làm.",
  "The closure cutoff has been reached.": "Đã đến mốc chốt bài.",
  "A closure workflow is pending, so a new Attempt cannot be created.":
    "Quy trình chốt bài đang chờ xử lý nên không thể tạo bài làm mới.",
  "A result is not available for this attempt.":
    "Chưa có kết quả cho bài làm này.",
  "Answer review opens after the assessment is closed.":
    "Chỉ được xem lại câu trả lời sau khi bài kiểm tra được đóng.",
  "Too many requests. Please wait, then retry.":
    "Có quá nhiều yêu cầu. Hãy đợi rồi thử lại.",
  "A required part of Quizopia is unavailable. You are still signed in.":
    "Một phần của Quizopia tạm không khả dụng. Bạn vẫn còn đăng nhập.",
  "Quizopia could not complete the request (HTTP {status}).":
    "Quizopia không thể hoàn tất yêu cầu (HTTP {status}).",
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
  "Live update hints are best-effort; refresh for the exact state.":
    "Cập nhật trực tiếp chỉ mang tính gợi ý; hãy làm mới để xem trạng thái chính xác.",
  "Candidate: {name}": "Thí sinh: {name}",
  "Remaining time": "Thời gian còn lại",
  Flagged: "Đã gắn cờ",
  "Flag question {number}": "Gắn cờ câu hỏi {number}",
  "Unflag question {number}": "Bỏ gắn cờ câu hỏi {number}",
  Flag: "Gắn cờ",
  Unflag: "Bỏ cờ",
} as const;
