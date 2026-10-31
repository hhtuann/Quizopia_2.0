// Teacher assessment/classroom/closure/notification/results catalog plus the
// student closure-notification inbox. Keys are the exact rendered English
// source text; values are Vietnamese presentation only. API enums (DRAFT,
// OPEN, CLOSED, CLASS, PUBLIC, PENDING, ...), canonical UUIDs, server error
// codes and authoritative UTC timestamp strings never pass through here.
export const assessmentMessages = {
  "Use 0 for unlimited attempts, or 1 to 10. This cannot change after opening.":
    "Nhập 0 để không giới hạn, hoặc từ 1 đến 10 lượt. Không thể đổi sau khi mở bài.",
  "Enter 0 for unlimited attempts, or a whole number from 1 to 10.":
    "Nhập 0 để không giới hạn, hoặc một số nguyên từ 1 đến 10.",
  "This assessment has unlimited duration, which stays unchanged; monitoring cannot be enabled.":
    "Bài kiểm tra này giữ thời lượng không giới hạn; không thể bật giám sát.",
  // Compact private locator (FB-010)
  "Private locator revealed": "Đã hiện locator",
  "Private locator hidden": "Locator đang ẩn",
  "Show locator": "Hiện locator",
  "Hide locator": "Ẩn locator",
  "Copy locator": "Sao chép locator",
  "Copied.": "Đã sao chép.",
  "Copy failed. Reveal the locator and copy it manually.":
    "Không sao chép được. Hãy hiện locator rồi sao chép thủ công.",
  "Share only with students you want to admit.":
    "Chỉ chia sẻ cho học sinh bạn muốn nhận vào bài kiểm tra.",
  // Results-first detail workspace (FB-009/011)
  "Refreshing…": "Đang làm mới…",
  Refresh: "Làm mới",
  "Could not refresh": "Không làm mới được",
  "The details below may be outdated.":
    "Thông tin bên dưới có thể chưa cập nhật.",
  "Retrying safely": "Đang thử lại an toàn",
  "Outcome unclear": "Chưa rõ kết quả",
  "Action was not completed": "Thao tác chưa hoàn tất",
  "Check the latest state first": "Hãy kiểm tra trạng thái mới nhất trước",
  "This action may have already succeeded. Refresh to see the current state; retrying repeats only your original request.":
    "Thao tác này có thể đã thành công. Làm mới để xem trạng thái hiện tại; thử lại chỉ lặp lại đúng yêu cầu gốc của bạn.",
  "Retrying…": "Đang thử lại…",
  Retry: "Thử lại",
  "Check now": "Kiểm tra ngay",
  Student: "Học sinh",
  "Student ID shown because no name is available":
    "Hiện mã học sinh vì chưa có tên",
  Configuration: "Cấu hình",
  "Choose a monitoring option first": "Hãy chọn chế độ giám sát trước",
  "Edit this DRAFT and choose enabled or disabled before OPEN.":
    "Chỉnh bản nháp này và chọn bật hoặc tắt giám sát trước khi mở.",
  "Assessment closed": "Đã kết thúc bài kiểm tra",
  "Students who submitted can now review their answers according to the review policy.":
    "Học sinh đã nộp bài có thể xem lại câu trả lời theo chính sách xem lại.",
  "Please sign in again to view results.":
    "Vui lòng đăng nhập lại để xem kết quả.",
  "Configuration and actions": "Cấu hình và thao tác",
  "Opening freezes the quiz version, audience and monitoring. Students can start when the participation window opens.":
    "Mở bài sẽ cố định phiên bản câu hỏi, đối tượng và giám sát. Học sinh làm bài khi đến thời gian bắt đầu.",
  "Closing ends new attempts. Students with active attempts may prevent closure; no one’s work is submitted or discarded by this action.":
    "Kết thúc sẽ không nhận lượt làm mới. Học sinh đang làm bài có thể chặn việc kết thúc; thao tác này không nộp hay hủy bài của ai.",
  "Schedule was changed": "Lịch đã thay đổi",
  // My Assessments directory: hero, filters, table, states
  "Manage your assessments, schedules and results.":
    "Quản lý bài kiểm tra, lịch trình và kết quả của bạn.",
  "Create assessment": "Tạo bài kiểm tra",
  "To create an assessment, choose a published version in your Quiz Library.":
    "Để tạo bài kiểm tra, hãy chọn một phiên bản đã xuất bản trong Thư viện bộ câu hỏi.",
  "Search assessments": "Tìm bài kiểm tra",
  "Publication state": "Trạng thái Publication",
  "All states": "Tất cả trạng thái",
  Search: "Tìm kiếm",
  "Assessments could not be loaded": "Không thể tải danh sách bài kiểm tra",
  "Retry assessments": "Thử tải lại bài kiểm tra",
  "Owned assessments": "Bài kiểm tra do bạn sở hữu",
  Assessment: "Bài kiểm tra",
  "State / audience": "Trạng thái / đối tượng",
  "Participation window": "Thời gian tham gia",
  Actions: "Thao tác",
  "Quiz version {versionNumber}": "Phiên bản bộ câu hỏi {versionNumber}",
  Classroom: "Lớp học",
  Public: "Công khai",
  "Start:": "Bắt đầu:",
  "End:": "Kết thúc:",
  Manage: "Quản lý",
  Results: "Kết quả",
  "Manage {title}": "Quản lý {title}",
  "Results for {title}": "Kết quả của {title}",
  "No matching assessments": "Không có bài kiểm tra phù hợp",
  "No assessments yet": "Chưa có bài kiểm tra nào",
  "Try another title or state to find your assessment.":
    "Thử tiêu đề hoặc trạng thái khác để tìm bài kiểm tra của bạn.",
  "Create your first assessment from a published QuizVersion.":
    "Tạo bài kiểm tra đầu tiên từ một QuizVersion đã xuất bản.",
  "Clear filters": "Xóa bộ lọc",
  "Open Quiz Library": "Mở Thư viện bộ câu hỏi",
  "Loading assessments…": "Đang tải bài kiểm tra…",
  "{count} assessments shown": "Đã hiển thị {count} bài kiểm tra",
  "Load more assessments": "Tải thêm bài kiểm tra",
  // Publication API failure presentation (publicationFailureMessage)
  "Your session changed or could not be refreshed. Please sign in again.":
    "Phiên đăng nhập của bạn đã thay đổi hoặc không thể làm mới. Hãy đăng nhập lại.",
  "The request was cancelled and may still have gone through.":
    "Yêu cầu đã bị hủy và có thể vẫn đã được xử lý.",
  "The connection is temporarily unavailable. You are still signed in.":
    "Kết nối tạm thời không khả dụng. Bạn vẫn còn đăng nhập.",
  "Check the configuration and revision before sending this request.":
    "Kiểm tra cấu hình và revision trước khi gửi yêu cầu này.",
  "The response could not be verified. Refresh before retrying a change.":
    "Không thể xác minh phản hồi. Hãy làm mới trước khi thử lại thay đổi.",
  "Active attempts prevent closing. Refresh after learners finish; there is no force-close.":
    "Các lượt làm đang hoạt động khiến không thể đóng. Hãy làm mới sau khi người học hoàn tất; không có tùy chọn đóng ép.",
  "A closure request is pending. Schedule changes are blocked; manage it on its own page.":
    "Một yêu cầu đóng đang chờ xử lý. Thay đổi lịch trình bị chặn; hãy quản lý nó trên trang riêng của nó.",
  "This Publication changed. Refresh and review the current revision before trying again.":
    "Publication này đã thay đổi. Hãy làm mới và xem lại revision hiện tại trước khi thử lại.",
  "This request conflicts with an earlier one. Do not repeat it with different settings.":
    "Yêu cầu này xung đột với một yêu cầu trước đó. Đừng lặp lại với cài đặt khác.",
  "Too many requests. Wait a moment, then retry the same action.":
    "Có quá nhiều yêu cầu. Hãy đợi một lát rồi thử lại đúng thao tác.",
  "This classroom is unavailable or not yours to use. Choose another classroom or retry.":
    "Lớp học này không khả dụng hoặc không do bạn sở hữu. Hãy chọn lớp khác hoặc thử lại.",
  "The selected quiz version is unavailable or no longer accessible to you.":
    "Phiên bản bộ câu hỏi đã chọn không khả dụng hoặc bạn không còn quyền truy cập.",
  "This Publication is unavailable or you do not have permission to view it.":
    "Publication này không khả dụng hoặc bạn không có quyền xem.",
  "You need the Teacher role and ownership for this action.":
    "Bạn cần vai trò Giáo viên và quyền sở hữu để thực hiện thao tác này.",
  "The configuration was rejected. Check the fields and try again.":
    "Cấu hình bị từ chối. Hãy kiểm tra các trường và thử lại.",
  "Part of Quizopia is temporarily unavailable. You are still signed in.":
    "Một phần của Quizopia tạm không khả dụng. Bạn vẫn còn đăng nhập.",
  "Quizopia could not complete the operation (HTTP {status}). Refresh before retrying.":
    "Quizopia không thể hoàn tất thao tác (HTTP {status}). Hãy làm mới trước khi thử lại.",
  // Assessment foundation failure presentation (assessmentFailureMessage)
  "The participation end has not passed yet, so delayed closure cannot be requested. Quizopia uses its own clock, not your device's.":
    "Chưa qua thời điểm kết thúc tham gia nên chưa thể yêu cầu đóng trễ. Quizopia dùng đồng hồ của hệ thống, không theo thiết bị của bạn.",
  "Some notices or 48-hour cutoffs are still pending. Progress so far is kept; refresh the request status.":
    "Vẫn còn thông báo hoặc mốc 48 giờ chưa xong. Tiến độ đã có được giữ nguyên; hãy làm mới trạng thái yêu cầu.",
  "A closure request is already live for this Publication. Manage that request instead of creating another.":
    "Một yêu cầu đóng đang hoạt động cho Publication này. Hãy quản lý yêu cầu đó thay vì tạo yêu cầu mới.",
  "Finalization has started, so this request can no longer be cancelled.":
    "Quá trình hoàn tất đã bắt đầu nên yêu cầu này không còn thể hủy.",
  "This closure request is already resolved. Start a new action if one is still required.":
    "Yêu cầu đóng này đã được xử lý xong. Hãy bắt đầu thao tác mới nếu vẫn cần.",
  "This closure request is unavailable or you do not have permission to view it.":
    "Yêu cầu đóng này không khả dụng hoặc bạn không có quyền xem.",
  "This notification is unavailable or does not belong to the current account.":
    "Thông báo này không khả dụng hoặc không thuộc về tài khoản hiện tại.",
  "You need the right role and ownership for this action.":
    "Bạn cần vai trò và quyền sở hữu phù hợp để thực hiện thao tác này.",
  "The request was malformed. Nothing was changed.":
    "Yêu cầu sai định dạng. Không có gì thay đổi.",
  "Check the identifier, revision and query before sending this request.":
    "Kiểm tra định danh, revision và truy vấn trước khi gửi yêu cầu này.",
  "This action is not allowed for your account or role.":
    "Thao tác này không được phép cho tài khoản hoặc vai trò của bạn.",
  "The resource is unavailable or not accessible for the current account.":
    "Tài nguyên không khả dụng hoặc không truy cập được cho tài khoản hiện tại.",
  // Owned Classroom selector
  "Choose one Classroom": "Chọn một lớp học",
  "Only classrooms you own are shown. Pick one for this assessment.":
    "Chỉ hiển thị lớp học do bạn sở hữu. Hãy chọn một lớp cho bài kiểm tra này.",
  "Classroom access unavailable": "Không thể truy cập lớp học",
  "Selected Classroom needs verification": "Lớp học đã chọn cần được xác minh",
  "Checking access to the saved Classroom…":
    "Đang kiểm tra quyền truy cập lớp học đã lưu…",
  "Retry selected Classroom": "Thử lại lớp học đã chọn",
  "Loading your Classrooms…": "Đang tải các lớp học của bạn…",
  "Classrooms could not be loaded": "Không thể tải các lớp học",
  "Retry Classrooms": "Thử tải lại lớp học",
  "No classrooms yet. Create a classroom first, or save this assessment as PUBLIC.":
    "Chưa có lớp học nào. Hãy tạo lớp học trước, hoặc lưu bài kiểm tra này dạng PUBLIC.",
  "Load more Classrooms": "Tải thêm lớp học",
  // Publication creation screen
  "Create Assessment Publication": "Tạo Publication bài kiểm tra",
  "Configure a DRAFT from the selected version. OPEN comes later.":
    "Cấu hình bản DRAFT từ phiên bản đã chọn. OPEN là bước sau.",
  "Selected QuizVersion could not be verified":
    "Không thể xác minh QuizVersion đã chọn",
  "The version could not be reached. Your selection was not changed.":
    "Không truy cập được phiên bản. Lựa chọn của bạn không bị thay đổi.",
  "Retry selected version": "Thử lại phiên bản đã chọn",
  "Verifying selected QuizVersion": "Đang xác minh QuizVersion đã chọn",
  "Verifying selected QuizVersion…": "Đang xác minh QuizVersion đã chọn…",
  "Publication confirmed": "Đã xác nhận Publication",
  "Created as {state}.": "Đã tạo với trạng thái {state}.",
  "Open the assessment": "Mở bài kiểm tra",
  "Creation outcome is uncertain": "Kết quả tạo chưa xác định",
  "DRAFT was not confirmed": "DRAFT chưa được xác nhận",
  "This creation may have gone through. Retry the exact request below; settings stay locked. Leaving this page loses the safe retry key.":
    "Lần tạo này có thể đã thành công. Hãy thử lại đúng yêu cầu bên dưới; cài đặt bị khóa. Rời trang này sẽ mất khóa thử lại an toàn.",
  "Retrying original creation": "Đang thử lại yêu cầu tạo gốc",
  "Retry same creation": "Thử lại cùng một yêu cầu tạo",
  // Publication configuration form
  "This form has unsaved changes. Leave this page and discard them?":
    "Biểu mẫu này có thay đổi chưa lưu. Rời trang này và bỏ các thay đổi đó?",
  "Publication configuration": "Cấu hình Publication",
  "Publication details": "Chi tiết Publication",
  "Source: version {versionNumber}. Creating this assessment does not change your Quiz draft.":
    "Nguồn: phiên bản {versionNumber}. Việc tạo bài kiểm tra này không thay đổi bản nháp bộ câu hỏi của bạn.",
  "Publication title": "Tiêu đề Publication",
  "Description (optional)": "Mô tả (tùy chọn)",
  "Audience and monitoring": "Đối tượng và giám sát",
  Audience: "Đối tượng",
  "Classroom monitoring choice": "Lựa chọn giám sát lớp học",
  "Choose later — DRAFT only": "Chọn sau — chỉ DRAFT",
  "Disabled — no monitoring": "Tắt — không giám sát",
  "Enabled — Activity Evidence + LiveKit Camera":
    "Bật — bằng chứng hoạt động + camera LiveKit",
  "Choose before OPEN. Enabling here does not start collection — students approve monitoring separately during the attempt.":
    "Chọn trước khi OPEN. Bật ở đây chưa bắt đầu thu thập — học sinh sẽ phê duyệt giám sát riêng khi làm bài.",
  "Authenticated participation": "Tham gia đã xác thực",
  "PUBLIC has no Classroom and no monitoring. Students must be signed in; a private locator is not anonymous access.":
    "PUBLIC không có lớp học và không giám sát. Học sinh phải đăng nhập; locator riêng tư không phải truy cập ẩn danh.",
  "Participation schedule": "Lịch tham gia",
  "Times use your local timezone. The end time does not close the assessment or shorten a running attempt.":
    "Thời gian hiển thị theo múi giờ địa phương của bạn. Thời điểm kết thúc không tự đóng bài kiểm tra hay rút ngắn bài làm đang diễn ra.",
  "Participation start": "Bắt đầu tham gia",
  "Participation end": "Kết thúc tham gia",
  "Existing unlimited duration": "Thời lượng không giới hạn hiện có",
  "This assessment has unlimited attempts, which stays unchanged; monitoring cannot be enabled.":
    "Bài kiểm tra này có lượt làm không giới hạn (giữ nguyên); không thể bật giám sát.",
  "Whole minutes from 5 to 240. Each attempt's deadline starts when the student begins.":
    "Số phút nguyên từ 5 đến 240. Hạn chót từng lượt làm bắt đầu khi học sinh vào bài.",
  "Attempt duration (minutes)": "Thời lượng Attempt (phút)",
  unlimited: "không giới hạn",
  "Maximum Attempts: {attempts}. This setting stays fixed.":
    "Số Attempts tối đa: {attempts}. Cài đặt này giữ nguyên.",
  "Check the highlighted fields": "Kiểm tra các trường được đánh dấu",
  "The configuration was not sent. Review the field messages above.":
    "Cấu hình chưa được gửi. Hãy xem lại các thông điệp trường ở trên.",
  "Publication summary": "Tóm tắt Publication",
  "New Publication": "Publication mới",
  "Edit DRAFT": "Sửa DRAFT",
  "Quiz version": "Phiên bản bộ câu hỏi",
  Duration: "Thời lượng",
  Unlimited: "Không giới hạn",
  "Review and create DRAFT": "Xem lại và tạo DRAFT",
  "Review DRAFT changes": "Xem lại thay đổi DRAFT",
  "Saving Publication": "Đang lưu Publication",
  "Create DRAFT": "Tạo DRAFT",
  "Save DRAFT": "Lưu DRAFT",
  "Review new Publication": "Xem lại Publication mới",
  "This saves a DRAFT only. OPEN is a separate reviewed action.":
    "Thao tác này chỉ lưu một DRAFT. OPEN là một thao tác được xem xét riêng.",
  "Enter a whole number from 5 to 240 minutes.":
    "Nhập một số nguyên từ 5 đến 240 phút.",
  "Select an accessible owned Classroom before continuing.":
    "Chọn một lớp học do bạn sở hữu và truy cập được trước khi tiếp tục.",
  // Schema validation messages localized at render time
  "Enter a title.": "Nhập tiêu đề.",
  "Use a valid instant with an explicit offset and at most six fractional digits.":
    "Dùng một thời điểm hợp lệ có offset rõ ràng và tối đa sáu chữ số thập phân.",
  "Participation end must be after start.":
    "Thời điểm kết thúc tham gia phải sau thời điểm bắt đầu.",
  "Enter one canonical Classroom ID.": "Nhập một mã lớp học chuẩn (canonical).",
  "PUBLIC has no Classroom and requires monitoring disabled.":
    "PUBLIC không có lớp học và yêu cầu tắt giám sát.",
  "Unlimited Attempts cannot enable monitoring.":
    "Attempts không giới hạn không thể bật giám sát.",
  // Publication detail screen
  "Loading Publication": "Đang tải Publication",
  "Loading details…": "Đang tải chi tiết…",
  "Publication could not be loaded": "Không thể tải Publication",
  "Publication details are unavailable.":
    "Chi tiết Publication không khả dụng.",
  "Retry Publication details": "Thử tải lại chi tiết Publication",
  "Assessment Publication": "Publication bài kiểm tra",
  "Revision {revision}": "Revision {revision}",
  "Refreshing details": "Đang làm mới chi tiết",
  "Refresh status": "Làm mới trạng thái",
  "Publication ID: {id}": "Mã Publication: {id}",
  "Current state could not be refreshed":
    "Không thể làm mới trạng thái hiện tại",
  "Operation was not confirmed": "Thao tác chưa được xác nhận",
  "Reconcile before continuing": "Đối chiếu trước khi tiếp tục",
  "Reconcile state": "Đối chiếu trạng thái",
  "Retrying original command": "Đang thử lại lệnh gốc",
  "Retry original command": "Thử lại lệnh gốc",
  "Review and OPEN": "Xem lại và OPEN",
  "Explicit monitoring choice required": "Yêu cầu chọn giám sát rõ ràng",
  "Edit this CLASS DRAFT and choose enabled or disabled before OPEN.":
    "Hãy sửa DRAFT CLASS này và chọn bật hoặc tắt trước khi OPEN.",
  "Edit DRAFT configuration": "Sửa cấu hình DRAFT",
  "Your edits are based on revision {base}; the current revision is {current}. The quiz version stays the same.":
    "Chỉnh sửa của bạn dựa trên revision {base}; revision hiện tại là {current}. Phiên bản bộ câu hỏi giữ nguyên.",
  "Review the latest configuration": "Xem lại cấu hình mới nhất",
  "Your entered values remain below. They will not be replayed against a newer revision.":
    "Các giá trị bạn đã nhập vẫn còn bên dưới. Chúng sẽ không được gửi lại trên một revision mới hơn.",
  "Use the latest configuration (discard my edits)":
    "Dùng cấu hình mới nhất (bỏ chỉnh sửa của tôi)",
  "The Publication is no longer editable.":
    "Publication này không còn chỉnh sửa được.",
  "This form has unsaved changes. Discard them and stop editing?":
    "Biểu mẫu này có thay đổi chưa lưu. Bỏ các thay đổi đó và dừng chỉnh sửa?",
  "Cancel editing": "Dừng chỉnh sửa",
  "Extend participation schedule": "Gia hạn lịch tham gia",
  "Hide schedule extension": "Ẩn gia hạn lịch trình",
  "The schedule extension has unsaved values. Hide it and discard them?":
    "Phần gia hạn lịch trình có giá trị chưa lưu. Ẩn nó và bỏ các giá trị đó?",
  "Review and CLOSE": "Xem lại và CLOSE",
  "Manage delayed closure": "Quản lý đóng trễ",
  "View attempt results": "Xem kết quả các Attempt",
  "Schedule revision changed": "Revision lịch trình đã thay đổi",
  "Your extension values remain below and will not be applied to a newer revision.":
    "Các giá trị gia hạn của bạn vẫn còn bên dưới và sẽ không được áp dụng cho một revision mới hơn.",
  "Use the latest schedule (discard my extension)":
    "Dùng lịch trình mới nhất (bỏ gia hạn của tôi)",
  "Publication CLOSED": "Publication đã CLOSED",
  "The assessment is now CLOSED.": "Bài kiểm tra đã CLOSED.",
  "Closure is confirmed. Eligible students who submitted can now review their answers.":
    "Đã xác nhận đóng bài. Học sinh đã nộp bài đủ điều kiện giờ có thể xem lại câu trả lời.",
  "OPEN Publication": "OPEN Publication",
  "CLOSE Publication": "CLOSE Publication",
  "Review and OPEN Publication": "Xem lại và OPEN Publication",
  "Confirm direct CLOSE": "Xác nhận CLOSE trực tiếp",
  "Sending reviewed operation…": "Đang gửi thao tác đã xem xét…",
  "Publication change confirmed.": "Đã xác nhận thay đổi Publication.",
  "Too many requests. Checking the current state; retry repeats your original request.":
    "Có quá nhiều yêu cầu. Đang kiểm tra trạng thái hiện tại; thử lại sẽ lặp lại đúng yêu cầu gốc.",
  "Outcome unclear. Checking the current state before another action.":
    "Chưa rõ kết quả. Đang kiểm tra trạng thái hiện tại trước thao tác khác.",
  "Operation was not confirmed.": "Thao tác chưa được xác nhận.",
  // Schedule extension
  "This schedule extension has unsaved values. Leave this page and discard them?":
    "Phần gia hạn lịch trình này có giá trị chưa lưu. Rời trang này và bỏ các giá trị đó?",
  "Extend OPEN schedule": "Gia hạn lịch OPEN",
  "Start stays fixed; end and duration can only increase. Existing attempt deadlines are not recalculated. A pending closure request may block changes.":
    "Thời điểm bắt đầu giữ nguyên; thời điểm kết thúc và thời lượng chỉ được tăng. Hạn chót các lượt làm hiện có không đổi. Yêu cầu đóng đang chờ có thể chặn thay đổi.",
  "Schedule extension": "Gia hạn lịch trình",
  "Current end: {end}. Leave unchanged to keep it exactly.":
    "Thời điểm kết thúc hiện tại: {end}. Để nguyên nếu muốn giữ chính xác.",
  "Extended participation end": "Kết thúc tham gia đã gia hạn",
  "Extended Attempt duration (minutes)": "Thời lượng Attempt đã gia hạn (phút)",
  "Attempt duration remains unlimited.":
    "Thời lượng Attempt vẫn không giới hạn.",
  "Enter a valid local participation end date and time. Nonexistent local times (DST gaps) are not accepted.":
    "Nhập ngày và giờ kết thúc tham gia hợp lệ theo giờ địa phương. Giờ địa phương không tồn tại (khoảng chuyển DST) không được chấp nhận.",
  "Participation end cannot decrease and must resolve to a valid instant.":
    "Thời điểm kết thúc tham gia không thể giảm và phải quy về một thời điểm hợp lệ.",
  "Duration cannot decrease, exceed 240, or switch bounded/unlimited mode.":
    "Thời lượng không thể giảm, vượt 240, hoặc chuyển đổi giữa chế độ giới hạn/không giới hạn.",
  "Check the schedule extension": "Kiểm tra phần gia hạn lịch trình",
  "Saving schedule extension": "Đang lưu gia hạn lịch trình",
  "Review schedule extension": "Xem lại gia hạn lịch trình",
  "Save schedule extension": "Lưu gia hạn lịch trình",
  "Review OPEN schedule extension": "Xem lại gia hạn lịch OPEN",
  "Existing Attempt deadlines remain unchanged.":
    "Các hạn chót Attempt đang có vẫn không đổi.",
  // Private locator
  "Private locator copied.": "Đã sao chép locator riêng tư.",
  "Clipboard access failed. Reveal the locator and copy it manually.":
    "Truy cập bảng tạm thất bại. Hãy hiển thị locator và sao chép thủ công.",
  "Private PUBLIC locator": "Locator PUBLIC riêng tư",
  "Hide private locator": "Ẩn locator riêng tư",
  "Reveal private locator": "Hiện locator riêng tư",
  "Copy private locator": "Sao chép locator riêng tư",
  // Publication summary dialog. "Description" and "Version {number}" reuse
  // the shared authoring catalog keys.
  Title: "Tiêu đề",
  "Source version": "Phiên bản nguồn",
  "Quiz ID": "Mã bộ câu hỏi",
  "QuizVersion ID": "Mã QuizVersion",
  "Classroom ID": "Mã lớp học",
  Monitoring: "Giám sát",
  "Attempt duration": "Thời lượng Attempt",
  "Maximum Attempts": "Số lượt làm tối đa",
  "No description": "Không có mô tả",
  "Not applicable (PUBLIC)": "Không áp dụng (PUBLIC)",
  "Enabled: Activity Evidence + LiveKit Camera":
    "Bật: bằng chứng hoạt động + camera LiveKit",
  "Not yet configured (DRAFT only)": "Chưa cấu hình (chỉ DRAFT)",
  Disabled: "Tắt",
  "Unlimited (existing configuration)": "Không giới hạn (cấu hình hiện có)",
  "{minutes} minutes": "{minutes} phút",
  // Workspace/authority gates shared by Teacher assessment screens
  "Teacher role and ownership are required. Switching workspace does not grant a role.":
    "Cần vai trò Giáo viên và quyền sở hữu. Chuyển không gian làm việc không cấp vai trò.",
  "You need the Teacher role and ownership of this assessment.":
    "Bạn cần vai trò Giáo viên và quyền sở hữu bài kiểm tra này.",
  "You need the Teacher role and ownership of this assessment. Switching workspace does not grant a role.":
    "Bạn cần vai trò Giáo viên và quyền sở hữu bài kiểm tra này. Chuyển không gian làm việc không cấp vai trò.",
  "Return to application": "Quay lại ứng dụng",
  "Switch to Teaching workspace": "Chuyển sang không gian Giảng dạy",
  "Assessment management lives in the Teaching workspace.":
    "Quản lý bài kiểm tra nằm trong không gian Giảng dạy.",
  "Closure management lives in the Teaching workspace.":
    "Quản lý đóng bài nằm trong không gian Giảng dạy.",
  "Teacher results live in the Teaching workspace.":
    "Kết quả của giáo viên nằm trong không gian Giảng dạy.",
  "Publication access unavailable": "Truy cập Publication không khả dụng",
  "Closure access unavailable": "Truy cập quy trình đóng không khả dụng",
  "Results unavailable": "Kết quả không khả dụng",
  // Delayed closure workflow
  "Back to Publication details": "Quay lại chi tiết Publication",
  "Closure workflow": "Quy trình đóng",
  "The closure workflow requires an assessment you own.":
    "Quy trình đóng yêu cầu một bài kiểm tra do bạn sở hữu.",
  "Delayed closure workflow": "Quy trình đóng trễ",
  "Sending your request…": "Đang gửi yêu cầu…",
  "Request created. The assessment is not closed yet; notices are being prepared.":
    "Đã tạo yêu cầu. Bài kiểm tra chưa bị đóng; đang chuẩn bị các thông báo.",
  "This request already existed; the existing one is shown.":
    "Yêu cầu này đã tồn tại; hiển thị yêu cầu đang có.",
  "Something changed. Checking the latest details before another action.":
    "Có thay đổi. Đang kiểm tra chi tiết mới nhất trước thao tác khác.",
  "Closure request was not created.": "Yêu cầu đóng chưa được tạo.",
  "Request outcome is uncertain": "Kết quả yêu cầu chưa xác định",
  "Request was not created": "Yêu cầu chưa được tạo",
  "This request may have gone through. Refresh shows the current state without repeating it. Retry repeats only your original request — if it already succeeded, the existing request is returned.":
    "Yêu cầu này có thể đã được xử lý. Làm mới chỉ đọc trạng thái hiện tại, không lặp lại thao tác. Thử lại chỉ gửi lại đúng yêu cầu gốc — nếu đã thành công, hệ thống trả về yêu cầu đang có.",
  "Retrying original request": "Đang thử lại yêu cầu gốc",
  "Retry original request": "Thử lại yêu cầu gốc",
  "Closure request created": "Đã tạo yêu cầu đóng",
  "Existing closure request": "Yêu cầu đóng đang tồn tại",
  "A successful request starts preparing student notices. It does":
    "Yêu cầu thành công sẽ bắt đầu chuẩn bị thông báo cho học sinh. Nó",
  not: "không",
  "close the assessment; track progress on the request page.":
    "đóng bài kiểm tra; hãy theo dõi tiến độ trên trang yêu cầu.",
  "Closure request ID": "Mã yêu cầu đóng",
  State: "Trạng thái",
  "Open closure request": "Mở yêu cầu đóng",
  "Unlimited attempt duration": "Thời lượng Attempt không giới hạn",
  "Yes — handled by delayed closure": "Có — do quy trình đóng trễ xử lý",
  "No — bounded attempts end at their own deadlines":
    "Không — các lượt làm giới hạn kết thúc theo hạn riêng",
  "Delayed closure can be requested only after the participation end has passed. Quizopia uses its own clock, not your device's; a too-early request is rejected.":
    "Chỉ có thể yêu cầu đóng trễ sau khi đã qua thời điểm kết thúc tham gia. Quizopia dùng đồng hồ của hệ thống, không theo thiết bị của bạn; yêu cầu quá sớm sẽ bị từ chối.",
  "Request closure": "Yêu cầu đóng",
  "A closure request covers every active unlimited attempt. Each student first gets a notice; their 48-hour period starts only after the notice is confirmed accessible. A student who cannot be verified can delay closure indefinitely — there is no force-close. While a request is live, schedule changes and new attempts are blocked.":
    "Yêu cầu đóng áp dụng cho mọi lượt làm không giới hạn đang hoạt động. Từng học sinh trước hết nhận một thông báo; thời gian 48 giờ chỉ bắt đầu sau khi thông báo được xác nhận là truy cập được. Học sinh không xác minh được có thể làm chậm việc đóng vô thời hạn — không có tùy chọn đóng ép. Khi yêu cầu đang hoạt động, thay đổi lịch trình và lượt làm mới bị chặn.",
  "Review and REQUEST closure": "Xem lại và REQUEST đóng",
  "Use direct CLOSE on Publication details":
    "Dùng CLOSE trực tiếp trên chi tiết Publication",
  "Closure requests require an OPEN Publication":
    "Yêu cầu đóng cần một Publication đang OPEN",
  "This assessment is {state}. Direct CLOSE stays on the":
    "Bài kiểm tra này đang {state}. CLOSE trực tiếp vẫn nằm trên trang",
  "page; delayed closure applies only while OPEN.":
    "trang; đóng trễ chỉ áp dụng khi OPEN.",
  "Open a known closure request": "Mở một yêu cầu đóng đã biết",
  "Requests are opened by ID. Paste a closure request ID you saved; ownership is verified.":
    "Mở yêu cầu theo mã. Dán mã yêu cầu đóng đã lưu; hệ thống sẽ xác minh quyền sở hữu.",
  "Enter the closure request ID exactly as saved.":
    "Nhập chính xác mã yêu cầu đóng đã lưu.",
  "REQUEST delayed closure": "REQUEST đóng trễ",
  "Confirm delayed closure request": "Xác nhận yêu cầu đóng trễ",
  "This request uses revision {revision}. Retries repeat exactly this request.":
    "Yêu cầu này dùng revision {revision}. Thử lại sẽ lặp lại đúng yêu cầu này.",
  "Requesting closure does not close the assessment yet. Each student first receives a notice; their 48-hour period starts only when that notice is confirmed accessible — not at this click.":
    "Yêu cầu đóng chưa đóng bài kiểm tra ngay. Từng học sinh trước hết nhận một thông báo; thời gian 48 giờ chỉ bắt đầu khi thông báo đó được xác nhận là truy cập được — không phải lúc nhấn nút này.",
  "A recipient who cannot be verified can block closure indefinitely; there is no force-close.":
    "Người nhận không thể xác minh có thể chặn việc đóng vô thời hạn; không có tùy chọn đóng ép.",
  // Closure request detail
  "Back to closure workflow": "Quay lại quy trình đóng",
  "Loading closure request": "Đang tải yêu cầu đóng",
  "Loading status…": "Đang tải trạng thái…",
  "Closure request could not be loaded": "Không thể tải yêu cầu đóng",
  "Closure request details are unavailable.":
    "Chi tiết yêu cầu đóng không khả dụng.",
  "Retry closure request": "Thử tải lại yêu cầu đóng",
  "Refreshing status": "Đang làm mới trạng thái",
  "Publication revision after this request: {revision}":
    "Revision của Publication sau yêu cầu này: {revision}",
  "Status could not be refreshed": "Không thể làm mới trạng thái",
  "The status above may be outdated.":
    "Trạng thái ở trên có thể chưa cập nhật.",
  "Command outcome is uncertain": "Kết quả lệnh chưa xác định",
  "This command may have gone through. Refresh shows the current state without repeating it. Retry repeats only your original command — if it already succeeded, nothing changes.":
    "Lệnh này có thể đã được xử lý. Làm mới chỉ đọc trạng thái hiện tại, không lặp lại thao tác. Thử lại chỉ gửi lại đúng lệnh gốc — nếu đã thành công, không có gì thay đổi.",
  "Request record": "Bản ghi yêu cầu",
  "Publication ID": "Mã Publication",
  "Requested at": "Thời điểm yêu cầu",
  "Finalization started at": "Thời điểm bắt đầu hoàn tất",
  "Not started": "Chưa bắt đầu",
  "Resolved at": "Thời điểm xử lý xong",
  "Not resolved": "Chưa xử lý xong",
  "Publication revision": "Revision Publication",
  "Per-student cutoffs and completion percentages are not shown in this workflow.":
    "Mốc thời gian từng học sinh và phần trăm hoàn tất không hiển thị trong quy trình này.",
  "Progress was saved, but the assessment is not CLOSED yet. Check the status and run COMPLETE again to continue.":
    "Đã lưu tiến độ, nhưng bài kiểm tra chưa CLOSED. Hãy kiểm tra trạng thái và chạy COMPLETE lần nữa để tiếp tục.",
  "The request was cancelled.": "Đã hủy yêu cầu.",
  "Sending your command…": "Đang gửi lệnh…",
  "Something changed. Checking the latest status before another action.":
    "Có thay đổi. Đang kiểm tra trạng thái mới nhất trước thao tác khác.",
  "The latest status confirms the command already took effect; nothing was repeated.":
    "Trạng thái mới nhất xác nhận lệnh đã có hiệu lực; không lặp lại gì cả.",
  "Manage this request": "Quản lý yêu cầu này",
  "COMPLETE processes the next batch of due work; only your command can make the final CLOSED transition. CANCEL works only before finalization starts.":
    "COMPLETE xử lý lô công việc đến hạn tiếp theo; chỉ lệnh của bạn mới có thể chuyển CLOSED cuối cùng. CANCEL chỉ khả dụng trước khi việc hoàn tất bắt đầu.",
  "Review and COMPLETE closure": "Xem lại và COMPLETE đóng",
  "Review and CANCEL request": "Xem lại và CANCEL yêu cầu",
  "Cancellation is unavailable because finalization has started.":
    "Không thể hủy vì quá trình hoàn tất đã bắt đầu.",
  "The request is FINALIZING and can no longer be cancelled. Use COMPLETE to continue.":
    "Yêu cầu đang FINALIZING và không còn thể hủy. Hãy dùng COMPLETE để tiếp tục.",
  "This request is resolved": "Yêu cầu này đã được xử lý xong",
  "Resolved requests stay resolved. If needed, start a new request with fresh notices and 48-hour periods.":
    "Yêu cầu đã xử lý sẽ không hoạt động lại. Nếu cần, hãy tạo yêu cầu mới với thông báo và thời gian 48 giờ mới.",
  "CANCEL closure request": "CANCEL yêu cầu đóng",
  "COMPLETE closure": "COMPLETE đóng",
  "Confirm cancellation": "Xác nhận hủy",
  "Confirm completion command": "Xác nhận lệnh hoàn tất",
  "This command uses revision {revision}. Retries repeat exactly this command.":
    "Lệnh này dùng revision {revision}. Thử lại sẽ lặp lại đúng lệnh này.",
  "Cancelling resolves this request. Students keep their notices as history only. A later replacement is a new request with new notices and 48-hour periods.":
    "Hủy sẽ kết thúc yêu cầu này. Học sinh vẫn giữ thông báo như lịch sử tham khảo. Thay thế sau này là một yêu cầu mới với thông báo và thời gian 48 giờ mới.",
  "COMPLETE processes due work in batches. If notices or cutoffs are still outstanding, it returns CLOSURE_NOT_READY and keeps progress. The assessment closes only when every attempt is finished and every included student is resolved.":
    "COMPLETE xử lý công việc đến hạn theo từng lô. Nếu còn thông báo hoặc mốc thời gian chưa xong, hệ thống trả về CLOSURE_NOT_READY và giữ nguyên tiến độ. Bài kiểm tra chỉ đóng khi mọi lượt làm đã kết thúc và mọi học sinh được ghi nhận đã xử lý xong.",
  "Type {phrase} to enable this action": "Gõ {phrase} để bật hành động này",
  // Closure model labels/descriptions rendered through localize
  Pending: "Đang chờ",
  Finalizing: "Đang hoàn tất",
  Cancelled: "Đã hủy",
  Completed: "Đã hoàn tất",
  "Resolved early": "Đã xử lý sớm",
  "The request is recorded. Student notices are prepared and confirmed one by one; the assessment is not CLOSED yet.":
    "Yêu cầu đã được ghi lại. Thông báo cho học sinh được chuẩn bị và xác nhận riêng từng người; bài kiểm tra chưa CLOSED.",
  "Finalization has started. Confirmed due notices now produce system submissions; cancelling is no longer possible.":
    "Quá trình hoàn tất đã bắt đầu. Các thông báo đến hạn đã xác nhận bắt đầu tạo các lần nộp của hệ thống; không còn thể hủy.",
  "The request was cancelled before finalization. Students keep their notices as history only.":
    "Yêu cầu đã bị hủy trước khi hoàn tất. Học sinh vẫn giữ thông báo như lịch sử tham khảo.",
  "Every attempt was finished and every included student resolved; your COMPLETE command closed the assessment.":
    "Mọi lượt làm đã kết thúc và mọi học sinh được ghi nhận đã xử lý xong; lệnh COMPLETE của bạn đã đóng bài kiểm tra.",
  "The workflow was resolved without its own cutoff path, alongside the normal closure.":
    "Quy trình được xử lý mà không qua đường mốc thời gian riêng, cùng với lần đóng thông thường.",
  // Student notification inbox and detail
  "Student access required": "Cần quyền Học sinh",
  "The inbox is for signed-in Student accounts.":
    "Hộp thư dành cho tài khoản Học sinh đã đăng nhập.",
  "This notice is for its recipient's Student account.":
    "Thông báo này dành cho tài khoản Học sinh của người nhận.",
  "The assessment inbox belongs to the authenticated recipient with current Student authority.":
    "Hộp thư bài kiểm tra thuộc về người nhận đã xác thực với quyền Học sinh hiện tại.",
  "This notice belongs to its recipient with current Student authority.":
    "Thông báo này thuộc về người nhận của nó với quyền Học sinh hiện tại.",
  "Inbox unavailable": "Hộp thư không khả dụng",
  "Notice unavailable": "Thông báo không khả dụng",
  "Assessment notifications": "Thông báo bài kiểm tra",
  "Your inbox": "Hộp thư của bạn",
  "Closure notices for your account. Reading a notice never starts or changes its 48-hour period.":
    "Thông báo chốt bài dành cho tài khoản của bạn. Việc đọc thông báo không bao giờ bắt đầu hay thay đổi thời gian 48 giờ của nó.",
  "When your teacher starts closing an assessment you are working on, a notice appears here.":
    "Khi giáo viên bắt đầu đóng một bài kiểm tra bạn đang làm, thông báo sẽ xuất hiện tại đây.",
  "Refreshing inbox": "Đang làm mới hộp thư",
  "Refresh inbox": "Làm mới hộp thư",
  "Inbox could not be read": "Không thể đọc hộp thư",
  "Loading notifications": "Đang tải thông báo",
  "Loading your assessment notices…":
    "Đang tải các thông báo bài kiểm tra của bạn…",
  "No assessment notices yet": "Chưa có thông báo bài kiểm tra nào",
  "Progress saved": "Đã lưu tiến độ",
  Published: "Thời điểm xuất bản",
  "48-hour period": "Thời gian 48 giờ",
  "Confirmed cutoff": "Mốc kết thúc đã xác nhận",
  "View notice": "Xem thông báo",
  "Loading more notices": "Đang tải thêm thông báo",
  "Load more notices": "Tải thêm thông báo",
  "Back to inbox": "Quay lại hộp thư",
  "Assessment notice": "Thông báo bài kiểm tra",
  "Loading notice": "Đang tải thông báo",
  "Loading your notice…": "Đang tải thông báo của bạn…",
  "Notice could not be loaded": "Không thể tải thông báo",
  "This notification is unavailable.": "Thông báo này không khả dụng.",
  "Retry notice": "Thử tải lại thông báo",
  "Refreshing notice": "Đang làm mới thông báo",
  "Refresh notice": "Làm mới thông báo",
  "Notice could not be refreshed": "Không thể làm mới thông báo",
  "Previously verified content remains visible below.":
    "Nội dung đã xác minh trước đó vẫn hiển thị bên dưới.",
  "Notice message": "Nội dung thông báo",
  Timeline: "Dòng thời gian",
  "Notice confirmed": "Thông báo đã xác nhận",
  "48-hour cutoff": "Mốc kết thúc 48 giờ",
  "Checked at": "Thời điểm kiểm tra",
  "(your local time)": "(giờ địa phương của bạn)",
  "Your notice is being prepared": "Thông báo của bạn đang được chuẩn bị",
  "The 48-hour period starts only when this notice is confirmed for you. Until then there is no cutoff — nothing is asked of you yet.":
    "Thời gian 48 giờ chỉ bắt đầu khi thông báo này được xác nhận với bạn. Trước đó chưa có mốc kết thúc — bạn chưa cần làm gì cả.",
  "Closure cancelled": "Đã hủy quy trình đóng",
  "The teacher cancelled this closure. This notice stays as history only.":
    "Giáo viên đã hủy yêu cầu đóng này. Thông báo chỉ còn là lịch sử.",
  "Cutoff reached; finalization pending":
    "Đã đến mốc kết thúc; đang chờ hoàn tất",
  "Outcome recorded": "Đã ghi nhận kết quả",
  "You submitted this attempt yourself.": "Chính bạn đã nộp Attempt này.",
  "You abandoned this attempt; it has no grade.":
    "Bạn đã bỏ dở Attempt này; nó không có điểm.",
  "The cutoff was reached. Refresh to see the finalization of saved answers.":
    "Đã đến mốc kết thúc. Hãy làm mới để xem việc hoàn tất các câu trả lời đã lưu.",
  "Your saved answers were submitted and graded by the system.":
    "Các câu trả lời đã lưu của bạn đã được hệ thống nộp và chấm.",
  "This workflow was resolved without a cutoff finalization.":
    "Quy trình này được xử lý mà không có bước hoàn tất theo mốc kết thúc.",
  "A result exists — open it from your attempt page.":
    "Đã có kết quả — mở từ trang bài làm của bạn.",
  References: "Tham chiếu",
  "Your attempt": "Bài làm của bạn",
  "Closure request": "Yêu cầu đóng",
  Publication: "Publication",
  "Result (when finalized)": "Kết quả (khi hoàn tất)",
  "Not available yet": "Chưa có",
  "Opening your attempt or result always re-checks your access. This notice stays readable as your history even if you leave the class or the assessment closes.":
    "Mở bài làm hay kết quả đều kiểm tra lại quyền của bạn. Thông báo này vẫn đọc được như lịch sử của bạn ngay cả khi bạn rời lớp hoặc bài kiểm tra đóng.",
  // Notice status labels rendered through localize
  "Notice preparing": "Đang chuẩn bị thông báo",
  "48-hour period confirmed": "Đã xác nhận thời gian 48 giờ",
  "Cutoff reached": "Đã đến mốc kết thúc",
  "You submitted": "Bạn đã nộp",
  "You abandoned": "Bạn đã bỏ dở",
  "Finalized by system": "Hệ thống đã hoàn tất",
  "Waiting for notice confirmation": "Đang chờ xác nhận thông báo",
  "No confirmed cutoff": "Chưa có mốc kết thúc đã xác nhận",
  // Cutoff remaining guidance composed from server timestamps
  "the cutoff has been reached": "đã đến mốc kết thúc",
  "at least 48 hours remain": "còn ít nhất 48 giờ",
  "about {duration} remain": "còn khoảng {duration}",
  "{count} hour": "{count} giờ",
  "{count} hours": "{count} giờ",
  "{count} minute": "{count} phút",
  "{count} minutes": "{count} phút",
  "less than a minute remains": "còn chưa đầy một phút",
  "Cutoff confirmed; refresh for the latest.":
    "Đã xác nhận mốc kết thúc; hãy làm mới để xem mới nhất.",
  // Teacher results table
  "Publication results": "Kết quả Publication",
  "Attempt results": "Kết quả các Attempt",
  "Each attempt with its exact total. ACTIVE and ABANDONED attempts have no result yet. Answers and answer keys are never shown here.":
    "Từng lượt làm với tổng điểm chính xác. Các lượt ACTIVE và ABANDONED chưa có kết quả. Không hiển thị câu trả lời hay đáp án ở đây.",
  "Refreshing results": "Đang làm mới kết quả",
  "Refresh results": "Làm mới kết quả",
  "Results could not be read": "Không thể đọc kết quả",
  "Loading results": "Đang tải kết quả",
  "Loading attempts…": "Đang tải các lượt làm…",
  "No attempt rows yet": "Chưa có dòng Attempt nào",
  "No attempts yet, or none are visible to your account.":
    "Chưa có lượt làm nào, hoặc không lượt nào hiển thị với tài khoản của bạn.",
  "Scrollable Publication attempt results":
    "Kết quả Attempt của Publication có thể cuộn",
  "Attempt rows with state and exact totals for this Publication":
    "Các dòng Attempt với trạng thái và tổng điểm chính xác cho Publication này",
  Ordinal: "Thứ tự",
  "Attempt ID": "Mã Attempt",
  "Student ID": "Mã học sinh",
  Score: "Điểm",
  Finalized: "Đã hoàn tất",
  "Open monitoring for Attempt {attemptId}":
    "Mở giám sát cho Attempt {attemptId}",
  "No result (still active)": "Chưa có kết quả (vẫn đang hoạt động)",
  "No result (ungraded)": "Chưa có kết quả (chưa chấm)",
  "Loading more rows": "Đang tải thêm dòng",
  "Load more rows": "Tải thêm dòng",
  "Choose an Attempt ID to open its monitoring page; access is verified separately each time.":
    "Chọn mã Attempt để mở trang giám sát của lượt đó; quyền truy cập được xác minh riêng mỗi lần.",
  // Attempt state and terminal reason labels rendered through localize
  Active: "Đang hoạt động",
  Submitted: "Đã nộp",
  Abandoned: "Đã bỏ dở",
  "Manual submission": "Nộp thủ công",
  "Bounded timeout": "Hết thời gian giới hạn",
  "Teacher notice cutoff": "Mốc thông báo của Giáo viên",
  "Student abandoned": "Học sinh bỏ dở",
  // Unified results-first Publication detail (UX-04) and the shared
  // PublicationResultsSection empty state
  "Configuration summary": "Tóm tắt cấu hình",
  "No results yet": "Chưa có kết quả nào",
  // W4F-T3 unified student results dashboard
  "Student results dashboard": "Bảng kết quả theo học sinh",
  "Student results": "Kết quả học sinh",
  "One row per student. Attempts, best and average scores come from the graded work of each student in this assessment.":
    "Mỗi dòng là một học sinh. Số lượt làm, điểm cao nhất và điểm trung bình lấy từ bài đã chấm của từng học sinh trong bài kiểm tra này.",
  "Summary could not be read": "Không thể đọc tổng quan",
  "Loading summary": "Đang tải tổng quan",
  "Loading summary…": "Đang tải tổng quan…",
  "Retry summary": "Thử lại tổng quan",
  "Active students": "Học sinh đang làm",
  "At least one attempt is still active.":
    "Có ít nhất một lượt làm vẫn đang hoạt động.",
  "Submitted students": "Học sinh đã nộp",
  "At least one submitted attempt.": "Có ít nhất một lượt làm đã nộp.",
  "Total participants": "Tổng người tham gia",
  "Distinct students with any attempt, including abandoned.":
    "Số học sinh riêng biệt có ít nhất một lượt làm, kể cả lượt bỏ dở.",
  "Graded students": "Học sinh đã có điểm",
  "At least one completely graded attempt.":
    "Có ít nhất một lượt làm đã chấm hoàn chỉnh.",
  "Best score": "Điểm cao nhất",
  "Best submitted score out of 10.": "Điểm cao nhất trên thang 10.",
  "No graded work yet.": "Chưa có bài đã chấm.",
  "Average best score": "Điểm cao nhất trung bình",
  "Mean of one best score per graded student.":
    "Trung bình của một điểm cao nhất cho mỗi học sinh đã chấm.",
  "A student who submitted and started a retake appears in both groups. Groups are never added together.":
    "Học sinh đã nộp rồi làm lại sẽ nằm ở cả hai nhóm. Các nhóm không bao giờ cộng lại với nhau.",
  "Score distribution": "Phân bố điểm",
  "Each graded student appears once with their best submitted score, in half-point ranges from 0 to 10.":
    "Mỗi học sinh đã chấm xuất hiện một lần với điểm đã nộp cao nhất, theo khoảng nửa điểm từ 0 đến 10.",
  "Score table": "Bảng điểm",
  "Score distribution table": "Bảng phân bố điểm",
  "Students per half-point score range, best submitted score per student":
    "Số học sinh theo từng khoảng điểm nửa điểm, một điểm đã nộp cao nhất mỗi học sinh",
  "Score range": "Khoảng điểm",
  Students: "Học sinh",
  "No graded work yet, so the distribution is empty. Scores appear after attempts are submitted and graded.":
    "Chưa có bài đã chấm nên phân bố trống. Điểm xuất hiện sau khi các lượt làm được nộp và chấm.",
  "Students could not be read": "Không thể đọc danh sách học sinh",
  "Retry students": "Thử lại danh sách học sinh",
  "Check the student ID": "Kiểm tra mã học sinh",
  "Enter a complete student ID (exact match) or clear the field to list everyone.":
    "Nhập đủ mã học sinh (khớp chính xác) hoặc bỏ trống để xem tất cả.",
  "Attempt state": "Trạng thái lượt làm",
  "Filter students by attempt state": "Lọc học sinh theo trạng thái lượt làm",
  "Sort by": "Sắp xếp theo",
  "Sort the student list": "Sắp xếp danh sách học sinh",
  "Last activity": "Hoạt động gần nhất",
  "Highest first": "Cao nhất trước",
  "Lowest first": "Thấp nhất trước",
  "Reverse sort order": "Đảo thứ tự sắp xếp",
  "Find student": "Tìm học sinh",
  "Find a student by their exact student ID":
    "Tìm học sinh theo đúng mã học sinh của họ",
  Find: "Tìm",
  "Showing the one student with this exact ID.":
    "Đang hiển thị học sinh duy nhất có đúng mã này.",
  "Clear student filter": "Bỏ lọc học sinh",
  "Loading students": "Đang tải học sinh",
  "Loading students…": "Đang tải học sinh…",
  "No students yet": "Chưa có học sinh nào",
  "No student in this assessment matches the current filters yet.":
    "Chưa có học sinh nào trong bài kiểm tra khớp với bộ lọc hiện tại.",
  "Student directory": "Danh sách học sinh",
  "One row per student with current state, attempts and exact graded scores":
    "Mỗi dòng một học sinh với trạng thái hiện tại, số lượt làm và điểm đã chấm chính xác",
  "Current attempt": "Lượt làm hiện tại",
  Attempts: "Lượt làm",
  "{count} graded": "{count} đã chấm",
  Average: "Trung bình",
  "Latest graded": "Chấm gần nhất",
  "Open attempt history": "Mở lịch sử lượt làm",
  "View attempts": "Xem các lượt làm",
  "Loading more students": "Đang tải thêm học sinh",
  "Load more students": "Tải thêm học sinh",
  "Attempt history": "Lịch sử lượt làm",
  "Close student details": "Đóng chi tiết học sinh",
  "Student attempts": "Các lượt làm của học sinh",
  "Attempt history could not be read": "Không thể đọc lịch sử lượt làm",
  "Loading attempt history": "Đang tải lịch sử lượt làm",
  "Loading attempt history…": "Đang tải lịch sử lượt làm…",
  "This student has no attempts in this assessment.":
    "Học sinh này không có lượt làm nào trong bài kiểm tra.",
  "Attempt history table": "Bảng lịch sử lượt làm",
  "Attempt history with state, timing, outcome and exact graded scores":
    "Lịch sử lượt làm với trạng thái, thời gian, kết thúc và điểm đã chấm chính xác",
  Attempt: "Lượt làm",
  Started: "Bắt đầu",
  Finished: "Hoàn tất",
  Outcome: "Kết thúc bởi",
  Details: "Chi tiết",
  "Due {time}": "Hạn {time}",
  "Best attempt": "Lượt tốt nhất",
  "Latest attempt": "Lượt mới nhất",
  "Review submitted work": "Xem bài đã nộp",
  "Activity evidence": "Minh chứng hoạt động",
  "Open activity evidence for Attempt {ordinal}":
    "Mở minh chứng hoạt động của lượt làm {ordinal}",
  "Loading more attempts": "Đang tải thêm lượt làm",
  "Load more attempts": "Tải thêm lượt làm",
  "Answers and answer keys are never shown in this list; they appear only inside an authorized submitted-attempt review.":
    "Câu trả lời và đáp án không bao giờ hiển thị trong danh sách này; chúng chỉ xuất hiện trong phần xem bài đã nộp được cấp quyền.",
  "Back to student results": "Quay lại kết quả học sinh",
  "Loading results…": "Đang tải kết quả…",
  // Teacher submitted-review truthful failure states and audit provenance
  "Sensitive review is not available in this environment. Nothing is shown.":
    "Phần xem nhạy cảm không khả dụng trong môi trường này. Không hiển thị gì.",
  "This attempt is not available to your account. Check that you still own this assessment.":
    "Lượt làm này không khả dụng với tài khoản của bạn. Hãy kiểm tra xem bạn vẫn còn sở hữu bài kiểm tra này.",
  "Only submitted and graded attempts can be reviewed here. Retakes in progress and abandoned attempts stay closed.":
    "Chỉ các lượt làm đã nộp và đã chấm mới xem được ở đây. Lượt làm lại đang diễn ra và lượt bỏ dở vẫn bị đóng.",
  "The review could not be read right now. No protected content is shown; try again.":
    "Hiện không đọc được phần xem. Không hiển thị nội dung được bảo vệ; hãy thử lại.",
  "Interaction timeline unavailable": "Lịch sử tương tác không khả dụng",
  "Server records are trusted facts. Browser reports are optional claims and never proof of misconduct; history may be incomplete.":
    "Bản ghi từ máy chủ là dữ liệu đáng tin. Báo cáo từ trình duyệt chỉ là thông tin tự nguyện và không bao giờ là bằng chứng vi phạm; lịch sử có thể thiếu.",
  "Server record": "Bản ghi máy chủ",
  "Browser report": "Báo cáo trình duyệt",
  "Server received": "Máy chủ nhận lúc",
  "Recorded at": "Ghi nhận lúc",
  "Attempt started": "Bắt đầu lượt làm",
  "Answer saved": "Đã lưu câu trả lời",
  "Attempt submitted": "Đã nộp lượt làm",
  "Attempt abandoned": "Đã bỏ lượt làm",
  "Closure notice confirmed": "Đã xác nhận thông báo đóng",
  "Result graded": "Đã chấm kết quả",
  "Question opened": "Mở câu hỏi",
  "Question left": "Rời câu hỏi",
  "Answer selected": "Chọn câu trả lời",
  "Answer changed": "Đổi câu trả lời",
  "Answer cleared": "Xóa câu trả lời",
  "Optional collection is off": "Thu thập tự nguyện đang tắt",
  "Earlier attempts have no history": "Các lượt làm cũ hơn không có lịch sử",
  "Some question types have no detail": "Một số dạng câu hỏi không có chi tiết",
  "Some browser reports are missing": "Thiếu một số báo cáo trình duyệt",
  "Some history has expired": "Một phần lịch sử đã hết hạn",
  "Detail available": "Có chi tiết",
  "No detail for this question type": "Không có chi tiết cho dạng câu hỏi này",
  "Detail missing": "Thiếu chi tiết",
  "Detail expired": "Chi tiết đã hết hạn",
  "No answer detail applies": "Không áp dụng chi tiết câu trả lời",
} as const;
