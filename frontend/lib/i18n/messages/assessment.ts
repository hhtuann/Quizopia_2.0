// Teacher assessment/classroom/closure/notification/results catalog plus the
// student closure-notification inbox. Keys are the exact rendered English
// source text; values are Vietnamese presentation only. API enums (DRAFT,
// OPEN, CLOSED, CLASS, PUBLIC, PENDING, ...), canonical UUIDs, server error
// codes and authoritative UTC timestamp strings never pass through here.
export const assessmentMessages = {
  // My Assessments directory: hero, filters, table, states
  "Manage your assessment Publications, schedules and results.":
    "Quản lý các Publication bài kiểm tra, lịch trình và kết quả của bạn.",
  "Create assessment": "Tạo bài kiểm tra",
  "To create an assessment, choose an immutable published version in your Quiz Library.":
    "Để tạo bài kiểm tra, hãy chọn một phiên bản đã xuất bản bất biến trong Thư viện bộ câu hỏi của bạn.",
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
  "Your session changed or could not be refreshed. Sign in or verify your account before continuing.":
    "Phiên đăng nhập của bạn đã thay đổi hoặc không thể làm mới. Hãy đăng nhập hoặc xác minh tài khoản trước khi tiếp tục.",
  "The request was cancelled. A mutation may still have reached the server.":
    "Yêu cầu đã bị hủy. Thao tác vẫn có thể đã đến được máy chủ.",
  "Verification or the connection is temporarily unavailable. Your account has not been logged out.":
    "Việc xác minh hoặc kết nối tạm thời không khả dụng. Tài khoản của bạn chưa bị đăng xuất.",
  "Check the configuration and revision before sending this request.":
    "Kiểm tra cấu hình và revision trước khi gửi yêu cầu này.",
  "The server response could not be verified. Refresh authoritative state before retrying a change.":
    "Không thể xác minh phản hồi của máy chủ. Hãy làm mới trạng thái chính thức trước khi thử lại thay đổi.",
  "Active Attempts prevent closure. Refresh status after learners finish; force-close is not available.":
    "Các Attempt đang hoạt động khiến không thể đóng. Hãy làm mới trạng thái sau khi người học hoàn tất; không có tùy chọn đóng ép.",
  "A closure workflow is pending. Schedule changes are blocked; manage that workflow in its separate experience.":
    "Một quy trình đóng đang chờ xử lý. Thay đổi lịch trình bị chặn; hãy quản lý quy trình đó trong trải nghiệm riêng của nó.",
  "This Publication changed. Refresh and review the current revision before trying again.":
    "Publication này đã thay đổi. Hãy làm mới và xem lại revision hiện tại trước khi thử lại.",
  "The Publication state or revision changed. Refresh authoritative details and review your action.":
    "Trạng thái hoặc revision của Publication đã thay đổi. Hãy làm mới chi tiết chính thức và xem lại thao tác của bạn.",
  "This operation identity conflicts with an earlier request. Do not repeat it with different settings.":
    "Danh tính thao tác này xung đột với một yêu cầu trước đó. Đừng lặp lại với cài đặt khác.",
  "The server is temporarily throttling this operation. Retry the same operation key after a short wait.":
    "Máy chủ tạm thời giới hạn thao tác này. Hãy thử lại cùng một khóa thao tác sau một lát.",
  "The Classroom is unavailable or you are not authorized to assign to it. Choose another owned Classroom or retry access verification.":
    "Lớp học không khả dụng hoặc bạn không có quyền gán vào lớp. Hãy chọn một lớp học khác do bạn sở hữu hoặc thử lại xác minh quyền truy cập.",
  "The selected immutable QuizVersion is unavailable or no longer accessible to you.":
    "QuizVersion bất biến đã chọn không khả dụng hoặc bạn không còn quyền truy cập.",
  "This Publication is unavailable or you do not have permission to view it.":
    "Publication này không khả dụng hoặc bạn không có quyền xem.",
  "The server denied this operation. Current Teacher authority and ownership are required.":
    "Máy chủ đã từ chối thao tác này. Cần quyền Giáo viên hiện tại và quyền sở hữu.",
  "The server rejected the configuration. Check the fields and try again.":
    "Máy chủ đã từ chối cấu hình. Hãy kiểm tra các trường và thử lại.",
  "Required authority, source or persistence is unavailable. Your account has not been logged out.":
    "Quyền, nguồn hoặc lưu trữ cần thiết không khả dụng. Tài khoản của bạn chưa bị đăng xuất.",
  "Your session could not authorize this request. Sign in again before continuing.":
    "Phiên của bạn không thể cấp quyền cho yêu cầu này. Hãy đăng nhập lại trước khi tiếp tục.",
  "The server could not complete the operation (HTTP {status}). Refresh status before retrying.":
    "Máy chủ không thể hoàn tất thao tác (HTTP {status}). Hãy làm mới trạng thái trước khi thử lại.",
  // Assessment foundation failure presentation (assessmentFailureMessage)
  "Active Attempts prevent direct closure. Manage the delayed closure workflow or refresh after learners finish; force-close is not available.":
    "Các Attempt đang hoạt động khiến không thể đóng trực tiếp. Hãy quản lý quy trình đóng trễ hoặc làm mới sau khi người học hoàn tất; không có tùy chọn đóng ép.",
  "A closure workflow is already live for this Publication. Manage that request instead of creating another.":
    "Một quy trình đóng đang hoạt động cho Publication này. Hãy quản lý yêu cầu đó thay vì tạo yêu cầu mới.",
  "This Publication changed on the server. Refresh authoritative state and review the current revision before trying again.":
    "Publication này đã thay đổi trên máy chủ. Hãy làm mới trạng thái chính thức và xem lại revision hiện tại trước khi thử lại.",
  "This operation identity conflicts with an earlier request. Do not repeat it with different settings; refresh state and start a new deliberate action.":
    "Danh tính thao tác này xung đột với một yêu cầu trước đó. Đừng lặp lại với cài đặt khác; hãy làm mới trạng thái và bắt đầu một thao tác chủ đích mới.",
  "The server's authoritative clock has not reached the participation end. Delayed closure cannot be requested yet; browser time is never used.":
    "Đồng hồ chính thức của máy chủ chưa đến thời điểm kết thúc tham gia. Chưa thể yêu cầu đóng trễ; thời gian trình duyệt không bao giờ được dùng.",
  "The server still waits on confirmed notices or 48-hour cutoffs. Already committed progress remains; refresh the request status.":
    "Máy chủ vẫn đang chờ thông báo được xác nhận hoặc mốc 48 giờ. Tiến độ đã cam kết vẫn được giữ; hãy làm mới trạng thái yêu cầu.",
  "Closure finalization has durably started, so this request can no longer be cancelled.":
    "Quá trình hoàn tất đóng đã bắt đầu một cách bền vững nên yêu cầu này không còn thể hủy được.",
  "This closure request is already resolved. Start a new deliberate action if one is still required.":
    "Yêu cầu đóng này đã được xử lý xong. Hãy bắt đầu một thao tác chủ đích mới nếu vẫn cần.",
  "This closure request is unavailable or you do not have permission to view it.":
    "Yêu cầu đóng này không khả dụng hoặc bạn không có quyền xem.",
  "This notification is unavailable or does not belong to the current account.":
    "Thông báo này không khả dụng hoặc không thuộc về tài khoản hiện tại.",
  "The server denied this operation. Current role authority and ownership are required.":
    "Máy chủ đã từ chối thao tác này. Cần quyền vai trò hiện tại và quyền sở hữu.",
  "The server rejected the request as malformed. No mutation occurred.":
    "Máy chủ đã từ chối yêu cầu vì định dạng không hợp lệ. Không có thay đổi nào được thực hiện.",
  "Check the identifier, revision and query before sending this request.":
    "Kiểm tra định danh, revision và truy vấn trước khi gửi yêu cầu này.",
  "Required authority, dependency, persistence or grading is unavailable. Your account has not been logged out.":
    "Quyền, phụ thuộc, lưu trữ hoặc chấm điểm cần thiết không khả dụng. Tài khoản của bạn chưa bị đăng xuất.",
  "The server denied this operation for the current account or role.":
    "Máy chủ đã từ chối thao tác này cho tài khoản hoặc vai trò hiện tại.",
  "The resource is unavailable or not accessible for the current account.":
    "Tài nguyên không khả dụng hoặc không truy cập được cho tài khoản hiện tại.",
  // Owned Classroom selector
  "Choose one Classroom": "Chọn một lớp học",
  "Only your owned Classrooms are available. Select one to assign this assessment.":
    "Chỉ các lớp học do bạn sở hữu mới khả dụng. Hãy chọn một lớp để gán bài kiểm tra này.",
  "Classroom access unavailable": "Không thể truy cập lớp học",
  "Your authenticated Classroom connection is unavailable.":
    "Kết nối lớp học đã xác thực của bạn không khả dụng.",
  "Selected Classroom needs verification": "Lớp học đã chọn cần được xác minh",
  "Checking access to the saved Classroom…":
    "Đang kiểm tra quyền truy cập lớp học đã lưu…",
  "Retry selected Classroom": "Thử lại lớp học đã chọn",
  "Loading your Classrooms…": "Đang tải các lớp học của bạn…",
  "Classrooms could not be loaded": "Không thể tải các lớp học",
  "Retry Classrooms": "Thử tải lại lớp học",
  "No owned Classrooms yet. A Classroom must be created before you can assign a CLASS assessment. You can save a PUBLIC assessment instead.":
    "Chưa có lớp học nào do bạn sở hữu. Cần tạo một lớp học trước khi gán bài kiểm tra CLASS. Bạn có thể lưu bài kiểm tra PUBLIC thay thế.",
  "Load more Classrooms": "Tải thêm lớp học",
  // Publication creation screen
  "Create Assessment Publication": "Tạo Publication bài kiểm tra",
  "Configure a DRAFT from the exact immutable version you selected. OPEN comes later.":
    "Cấu hình một bản DRAFT từ đúng phiên bản bất biến bạn đã chọn. OPEN là bước sau.",
  "Selected QuizVersion could not be verified":
    "Không thể xác minh QuizVersion đã chọn",
  "The version may be unavailable, inaccessible or temporarily unreachable. Your selection has not been replaced with a newer version.":
    "Phiên bản có thể không khả dụng, không truy cập được hoặc tạm thời không kết nối được. Lựa chọn của bạn không bị thay thế bằng phiên bản mới hơn.",
  "Retry selected version": "Thử lại phiên bản đã chọn",
  "Verifying selected QuizVersion": "Đang xác minh QuizVersion đã chọn",
  "Verifying selected QuizVersion…": "Đang xác minh QuizVersion đã chọn…",
  "Publication confirmed": "Đã xác nhận Publication",
  "The server confirmed {state}.": "Máy chủ đã xác nhận {state}.",
  "Open authoritative Publication details":
    "Mở chi tiết Publication chính thức",
  "Creation outcome is uncertain": "Kết quả tạo chưa xác định",
  "DRAFT was not confirmed": "DRAFT chưa được xác nhận",
  "The server may have created this Publication. Retry the exact original request below; settings are locked. Reloading or leaving this page loses the in-memory key and cannot safely locate an uncertain creation automatically.":
    "Máy chủ có thể đã tạo Publication này. Hãy thử lại đúng yêu cầu gốc bên dưới; cài đặt bị khóa. Tải lại hoặc rời trang này sẽ mất khóa trong bộ nhớ và không thể tự động định vị một lần tạo chưa xác định một cách an toàn.",
  "Retrying original creation": "Đang thử lại yêu cầu tạo gốc",
  "Retry same creation": "Thử lại cùng một yêu cầu tạo",
  // Publication configuration form
  "This form has unsaved changes. Leave this page and discard them?":
    "Biểu mẫu này có thay đổi chưa lưu. Rời trang này và bỏ các thay đổi đó?",
  "Publication configuration": "Cấu hình Publication",
  "Publication details": "Chi tiết Publication",
  "Immutable source: version {versionNumber}. Creating a Publication does not publish or modify the Quiz Draft.":
    "Nguồn bất biến: phiên bản {versionNumber}. Việc tạo Publication không xuất bản hay thay đổi bản nháp bộ câu hỏi.",
  "Publication title": "Tiêu đề Publication",
  "Description (optional)": "Mô tả (tùy chọn)",
  "Audience and monitoring": "Đối tượng và giám sát",
  Audience: "Đối tượng",
  "Classroom monitoring choice": "Lựa chọn giám sát lớp học",
  "Choose later — DRAFT only": "Chọn sau — chỉ DRAFT",
  "Disabled — no monitoring": "Tắt — không giám sát",
  "Enabled — Activity Evidence + LiveKit Camera":
    "Bật — bằng chứng hoạt động + camera LiveKit",
  "Choose explicitly before OPEN. Enabled records the Activity Evidence and LiveKit Camera bundle; collection, camera access and privacy readiness require separate runtime approval. This page does not activate monitoring.":
    "Chọn rõ ràng trước khi OPEN. Bật sẽ ghi gói bằng chứng hoạt động và camera LiveKit; việc thu thập, truy cập camera và sẵn sàng quyền riêng tư cần phê duyệt runtime riêng. Trang này không kích hoạt giám sát.",
  "Authenticated participation": "Tham gia đã xác thực",
  "PUBLIC has no Classroom and monitoring is disabled. Participants need current authenticated STUDENT authority. A private locator is not anonymous access.":
    "PUBLIC không có lớp học và giám sát bị tắt. Người tham gia cần quyền STUDENT đã xác thực hiện tại. Locator riêng tư không phải truy cập ẩn danh.",
  "Participation schedule": "Lịch tham gia",
  "Dates and times are shown in your local timezone and converted to UTC automatically. Participation end does not close the Publication or shorten an existing Attempt.":
    "Ngày và giờ hiển thị theo múi giờ địa phương của bạn và tự động chuyển sang UTC. Thời điểm kết thúc tham gia không đóng Publication hay rút ngắn Attempt đang có.",
  "Participation start": "Bắt đầu tham gia",
  "Participation end": "Kết thúc tham gia",
  "Existing unlimited duration": "Thời lượng không giới hạn hiện có",
  "This Publication has unlimited Attempts. This bounded workflow preserves that duration and cannot enable monitoring.":
    "Publication này có Attempts không giới hạn. Quy trình giới hạn này giữ nguyên thời lượng và không thể bật giám sát.",
  "Whole minutes from 5 to 240. The server sets each Attempt deadline when it starts.":
    "Số phút nguyên từ 5 đến 240. Máy chủ đặt hạn chót từng Attempt khi Attempt bắt đầu.",
  "Attempt duration (minutes)": "Thời lượng Attempt (phút)",
  unlimited: "không giới hạn",
  "Maximum Attempts: {attempts}. This workflow preserves that setting.":
    "Số Attempts tối đa: {attempts}. Quy trình này giữ nguyên cài đặt đó.",
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
  "Loading authoritative details…": "Đang tải chi tiết chính thức…",
  "Publication could not be loaded": "Không thể tải Publication",
  "Publication metadata is unavailable.":
    "Siêu dữ liệu Publication không khả dụng.",
  "Retry Publication details": "Thử tải lại chi tiết Publication",
  "Assessment Publication": "Publication bài kiểm tra",
  "Revision {revision}": "Revision {revision}",
  "Refreshing authoritative details": "Đang làm mới chi tiết chính thức",
  "Refresh status": "Làm mới trạng thái",
  "Publication ID: {id}": "Mã Publication: {id}",
  "OPEN finalizes the snapshot. Participation start/end and individual Attempt deadlines are separate. Window end never means CLOSED.":
    "OPEN hoàn tất bản chụp. Thời điểm bắt đầu/kết thúc tham gia và hạn chót từng Attempt là riêng biệt. Kết thúc cửa sổ không bao giờ đồng nghĩa CLOSED.",
  "Current state could not be refreshed":
    "Không thể làm mới trạng thái hiện tại",
  "Previously verified metadata remains visible; refresh before changing it.":
    "Siêu dữ liệu đã xác minh trước đó vẫn hiển thị; hãy làm mới trước khi thay đổi.",
  "Mutation identity retained": "Danh tính thao tác được giữ lại",
  "Mutation outcome is uncertain": "Kết quả thao tác chưa xác định",
  "Operation was not confirmed": "Thao tác chưa được xác nhận",
  "Reconcile before continuing": "Đối chiếu trước khi tiếp tục",
  "This operation may have committed. Refresh reads current authority; it never repeats a mutation. After a successful read, an explicit retry sends only the original command and revision":
    "Thao tác này có thể đã được cam kết. Làm mới đọc trạng thái chính thức hiện tại; nó không bao giờ lặp lại một thay đổi. Sau lần đọc thành công, một lần thử lại chủ đích chỉ gửi lệnh và revision gốc",
  " with the same idempotency key": " với cùng một khóa idempotency",
  ". No newer revision is substituted.":
    ". Không có revision mới hơn nào được thay thế.",
  "Reconcile state": "Đối chiếu trạng thái",
  "Retrying original command": "Đang thử lại lệnh gốc",
  "Retry original command": "Thử lại lệnh gốc",
  "Authoritative configuration": "Cấu hình chính thức",
  "Review and OPEN": "Xem lại và OPEN",
  "Explicit monitoring choice required": "Yêu cầu chọn giám sát rõ ràng",
  "Edit this CLASS DRAFT and choose enabled or disabled before OPEN.":
    "Hãy sửa DRAFT CLASS này và chọn bật hoặc tắt trước khi OPEN.",
  "Edit DRAFT configuration": "Sửa cấu hình DRAFT",
  "Your form is based on revision {base}; current server revision is {current}. Source version remains pinned.":
    "Biểu mẫu của bạn dựa trên revision {base}; revision hiện tại trên máy chủ là {current}. Phiên bản nguồn vẫn được ghim.",
  "Review the current server configuration":
    "Xem lại cấu hình máy chủ hiện tại",
  "Your entered values remain below. They will not be replayed against a newer revision.":
    "Các giá trị bạn đã nhập vẫn còn bên dưới. Chúng sẽ không được gửi lại trên một revision mới hơn.",
  "Use server configuration (discard local edits)":
    "Dùng cấu hình máy chủ (bỏ các chỉnh sửa cục bộ)",
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
  "Use server schedule (discard local extension)":
    "Dùng lịch trình máy chủ (bỏ gia hạn cục bộ)",
  "Publication CLOSED": "Publication đã CLOSED",
  "Durable closure is confirmed. Eligible submitted Students may review according to backend policy. Student results and answer-review UI are outside this Teacher workflow.":
    "Việc đóng bền vững đã được xác nhận. Học sinh đã nộp bài đủ điều kiện có thể xem lại theo chính sách backend. Kết quả và giao diện xem lại câu trả lời của học sinh nằm ngoài quy trình Giáo viên này.",
  "OPEN Publication": "OPEN Publication",
  "CLOSE Publication": "CLOSE Publication",
  "Review and OPEN Publication": "Xem lại và OPEN Publication",
  "Confirm direct CLOSE": "Xác nhận CLOSE trực tiếp",
  "OPEN finalizes an immutable, self-contained assessment snapshot. Source, audience and monitoring freeze; OPEN does not mean the participation window has started.":
    "OPEN hoàn tất một bản chụp bài kiểm tra bất biến và độc lập. Nguồn, đối tượng và giám sát bị đóng băng; OPEN không có nghĩa là cửa sổ tham gia đã bắt đầu.",
  "CLOSE is separate from participation-window end. Active Attempts may prevent closure. There is no force-close here. Durable CLOSED controls eligible Student answer review; this command does not ask the browser to submit or abandon anyone’s work.":
    "CLOSE tách biệt với thời điểm kết thúc cửa sổ tham gia. Các Attempt đang hoạt động có thể ngăn việc đóng. Không có đóng ép tại đây. CLOSED bền vững kiểm soát việc học sinh đủ điều kiện xem lại câu trả lời; lệnh này không yêu cầu trình duyệt nộp hay bỏ dở công việc của bất kỳ ai.",
  "Sending reviewed operation…": "Đang gửi thao tác đã xem xét…",
  "CLOSED confirmed by the server.": "Máy chủ đã xác nhận CLOSED.",
  "Publication change confirmed by the server.":
    "Máy chủ đã xác nhận thay đổi Publication.",
  "The server throttled direct CLOSE. Reading authoritative state before an explicit same-key retry.":
    "Máy chủ đã giới hạn CLOSE trực tiếp. Đang đọc trạng thái chính thức trước một lần thử lại chủ đích với cùng khóa.",
  "Outcome uncertain. Reading authoritative state before another action.":
    "Kết quả chưa xác định. Đang đọc trạng thái chính thức trước thao tác khác.",
  "Operation was not confirmed.": "Thao tác chưa được xác nhận.",
  "Authoritative details confirm the current state. No mutation was replayed.":
    "Chi tiết chính thức xác nhận trạng thái hiện tại. Không có thay đổi nào được gửi lại.",
  // Schedule extension
  "This schedule extension has unsaved values. Leave this page and discard them?":
    "Phần gia hạn lịch trình này có giá trị chưa lưu. Rời trang này và bỏ các giá trị đó?",
  "Extend OPEN schedule": "Gia hạn lịch OPEN",
  "Start remains fixed. End and duration can only stay the same or increase. Extensions apply to future starts and do not recalculate existing Attempt deadlines. Pending closure may block changes.":
    "Thời điểm bắt đầu giữ nguyên. Thời điểm kết thúc và thời lượng chỉ giữ nguyên hoặc tăng. Gia hạn áp dụng cho các lần bắt đầu trong tương lai và không tính lại hạn chót Attempt đang có. Yêu cầu đóng đang chờ có thể chặn thay đổi.",
  "Schedule extension": "Gia hạn lịch trình",
  "Current authoritative end: {end}. Leave unchanged to keep it exactly, including seconds and microseconds.":
    "Thời điểm kết thúc chính thức hiện tại: {end}. Để nguyên nếu muốn giữ chính xác, kể cả giây và microgiây.",
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
  "Owner-only metadata. Keep it private. Authenticated STUDENT authority is still required. A Student sharing URL is not available in this workflow.":
    "Siêu dữ liệu chỉ dành cho chủ sở hữu. Hãy giữ kín. Vẫn cần quyền STUDENT đã xác thực. Không có URL chia sẻ cho học sinh trong quy trình này.",
  "Hide private locator": "Ẩn locator riêng tư",
  "Reveal private locator": "Hiện locator riêng tư",
  "Copy private locator": "Sao chép locator riêng tư",
  // Publication summary dialog. "Description" and "Version {number}" reuse
  // the shared authoring catalog keys.
  Title: "Tiêu đề",
  "Immutable source": "Nguồn bất biến",
  "Quiz ID": "Mã bộ câu hỏi",
  "QuizVersion ID": "Mã QuizVersion",
  "Classroom ID": "Mã lớp học",
  Monitoring: "Giám sát",
  "Attempt duration": "Thời lượng Attempt",
  "Maximum Attempts": "Số Attempts tối đa",
  "No description": "Không có mô tả",
  "Not applicable (PUBLIC)": "Không áp dụng (PUBLIC)",
  "Enabled: Activity Evidence + LiveKit Camera":
    "Bật: bằng chứng hoạt động + camera LiveKit",
  "Not yet configured (DRAFT only)": "Chưa cấu hình (chỉ DRAFT)",
  Disabled: "Tắt",
  "Unlimited (existing configuration)": "Không giới hạn (cấu hình hiện có)",
  "{minutes} minutes": "{minutes} phút",
  // Workspace/authority gates shared by Teacher assessment screens
  "Current Teacher authority and ownership are required. Workspace selection does not grant a role.":
    "Cần quyền Giáo viên hiện tại và quyền sở hữu. Việc chọn không gian làm việc không cấp vai trò.",
  "Current Teacher authority and Publication ownership are required.":
    "Cần quyền Giáo viên hiện tại và quyền sở hữu Publication.",
  "Current Teacher authority and Publication ownership are required. Workspace selection does not grant a role.":
    "Cần quyền Giáo viên hiện tại và quyền sở hữu Publication. Việc chọn không gian làm việc không cấp vai trò.",
  "Return to application": "Quay lại ứng dụng",
  "Switch to Teaching workspace": "Chuyển sang không gian Giảng dạy",
  "Use your existing Teacher role to manage Publications. The server checks authority for every operation.":
    "Dùng vai trò Giáo viên hiện có của bạn để quản lý Publications. Máy chủ kiểm tra quyền cho mọi thao tác.",
  "The closure workflow uses your existing Teacher role. The server checks ownership and admission for every operation.":
    "Quy trình đóng dùng vai trò Giáo viên hiện có của bạn. Máy chủ kiểm tra quyền sở hữu và quyền vào cho mọi thao tác.",
  "Results are available to the owning Teacher; the server checks ownership for every read.":
    "Kết quả dành cho Giáo viên sở hữu; máy chủ kiểm tra quyền sở hữu cho mọi lượt đọc.",
  "Publication access unavailable": "Truy cập Publication không khả dụng",
  "Closure access unavailable": "Truy cập quy trình đóng không khả dụng",
  "Results unavailable": "Kết quả không khả dụng",
  "Your authenticated request service is unavailable. Sign in again before continuing.":
    "Dịch vụ yêu cầu đã xác thực của bạn không khả dụng. Hãy đăng nhập lại trước khi tiếp tục.",
  // Delayed closure workflow
  "Back to Publication details": "Quay lại chi tiết Publication",
  "Closure workflow": "Quy trình đóng",
  "The closure workflow requires an owner-authorized Publication.":
    "Quy trình đóng yêu cầu một Publication được chủ sở hữu ủy quyền.",
  "Delayed closure workflow": "Quy trình đóng trễ",
  "Sending reviewed closure request…": "Đang gửi yêu cầu đóng đã xem xét…",
  "Closure request created. The Publication is NOT closed; notices prepare independently.":
    "Đã tạo yêu cầu đóng. Publication CHƯA bị đóng; các thông báo được chuẩn bị độc lập.",
  "The server returned the existing request for this operation identity.":
    "Máy chủ đã trả về yêu cầu đang tồn tại cho danh tính thao tác này.",
  "The server throttled this operation. Reading authoritative state before an explicit same-key retry.":
    "Máy chủ đã giới hạn thao tác này. Đang đọc trạng thái chính thức trước một lần thử lại chủ đích với cùng khóa.",
  "The command conflicted with server state. Reading authoritative details before another action.":
    "Lệnh xung đột với trạng thái máy chủ. Đang đọc chi tiết chính thức trước thao tác khác.",
  "Closure request was not created.": "Yêu cầu đóng chưa được tạo.",
  "Request outcome is uncertain": "Kết quả yêu cầu chưa xác định",
  "Request was not created": "Yêu cầu chưa được tạo",
  "The closure request may have committed. Refresh reads current authority; it never repeats a mutation. An explicit retry re-sends only the original revision with the same idempotency key; a matching replay returns the existing request instead of creating another.":
    "Yêu cầu đóng này có thể đã được cam kết. Làm mới đọc trạng thái chính thức hiện tại; nó không bao giờ lặp lại một thay đổi. Một lần thử lại chủ đích chỉ gửi lại revision gốc với cùng khóa idempotency; một lần gửi lại trùng khớp sẽ trả về yêu cầu đang tồn tại thay vì tạo yêu cầu khác.",
  "Retrying original request": "Đang thử lại yêu cầu gốc",
  "Retry original request": "Thử lại yêu cầu gốc",
  "Closure request created": "Đã tạo yêu cầu đóng",
  "Existing closure request": "Yêu cầu đóng đang tồn tại",
  "A successful request records durable intent and starts recipient notice preparation. It does":
    "Một yêu cầu thành công ghi lại ý định bền vững và bắt đầu chuẩn bị thông báo cho người nhận. Nó",
  not: "không",
  "close the Publication; track progress on the request page.":
    "đóng Publication; hãy theo dõi tiến độ trên trang yêu cầu.",
  "Closure request ID": "Mã yêu cầu đóng",
  State: "Trạng thái",
  "Open closure request": "Mở yêu cầu đóng",
  "Authoritative window": "Cửa sổ chính thức",
  "Participation end (server authority)": "Kết thúc tham gia (theo máy chủ)",
  "Unlimited attempt duration": "Thời lượng Attempt không giới hạn",
  "Yes — captured by delayed closure": "Có — do quy trình đóng trễ ghi nhận",
  "No — bounded attempts finalize by their own deadlines":
    "Không — các Attempt giới hạn hoàn tất theo hạn chót riêng",
  "Delayed closure may be requested only after the server's authoritative clock passes the participation end. This screen never decides eligibility from browser time; the server rejects a request that is too early.":
    "Chỉ có thể yêu cầu đóng trễ sau khi đồng hồ chính thức của máy chủ vượt qua thời điểm kết thúc tham gia. Màn hình này không bao giờ quyết định đủ điều kiện theo giờ trình duyệt; máy chủ từ chối yêu cầu quá sớm.",
  "Request closure": "Yêu cầu đóng",
  "A closure request captures every currently ACTIVE unlimited Attempt and queues durable warning preparation for each recipient. The backend publishes each notice asynchronously and confirms it only after proving recipient access; only a confirmed recipient gets a 48-hour cutoff before eligible saved answers may be finalized. An unverifiable recipient may remain unconfirmed indefinitely. Zero captured recipients is valid. While a request is live, schedule changes and new attempts are blocked.":
    "Yêu cầu đóng ghi nhận mọi Attempt không giới hạn đang ACTIVE và xếp hàng chuẩn bị cảnh báo bền vững cho từng người nhận. Backend xuất bản mỗi thông báo không đồng bộ và chỉ xác nhận sau khi chứng minh được quyền truy cập của người nhận; chỉ người nhận được xác nhận mới có mốc 48 giờ trước khi các câu trả lời đã lưu đủ điều kiện có thể được hoàn tất. Người nhận không thể xác minh có thể giữ trạng thái chưa xác nhận vô thời hạn. Không ghi nhận người nhận nào cũng là hợp lệ. Khi một yêu cầu đang hoạt động, thay đổi lịch trình và Attempt mới bị chặn.",
  "Review and REQUEST closure": "Xem lại và REQUEST đóng",
  "Use direct CLOSE on Publication details":
    "Dùng CLOSE trực tiếp trên chi tiết Publication",
  "Closure requests require an OPEN Publication":
    "Yêu cầu đóng cần một Publication đang OPEN",
  "This Publication is {state}. Direct CLOSE and its reconciliation remain on the":
    "Publication này đang {state}. CLOSE trực tiếp và việc đối chiếu của nó vẫn nằm trên trang",
  "screen; a delayed-closure workflow applies only while OPEN.":
    "; quy trình đóng trễ chỉ áp dụng khi OPEN.",
  "Open a known closure request": "Mở một yêu cầu đóng đã biết",
  "There is no closure-request list API. Enter a known request UUID; the server verifies ownership.":
    "Không có API danh sách yêu cầu đóng. Hãy nhập UUID yêu cầu đã biết; máy chủ xác minh quyền sở hữu.",
  "Enter a canonical lowercase closure request UUID.":
    "Nhập UUID yêu cầu đóng chữ thường chuẩn (canonical).",
  "REQUEST delayed closure": "REQUEST đóng trễ",
  "Confirm delayed closure request": "Xác nhận yêu cầu đóng trễ",
  "Current authoritative revision: {revision}. The request will be sent with this revision and one durable operation key.":
    "Revision chính thức hiện tại: {revision}. Yêu cầu sẽ được gửi với revision này và một khóa thao tác bền vững.",
  "Requesting closure does not close the Publication. Each recipient first receives a durable notice; the 48-hour period for a recipient starts only when the server confirms their notice is genuinely accessible — never at this button click.":
    "Yêu cầu đóng không đóng Publication. Từng người nhận trước hết nhận một thông báo bền vững; thời gian 48 giờ của một người nhận chỉ bắt đầu khi máy chủ xác nhận thông báo của họ thực sự truy cập được — không bao giờ tại lần nhấn nút này.",
  "An ineligible, disabled or unverifiable recipient can block closure indefinitely; that accepted limitation has no force-close or automatic workaround.":
    "Một người nhận không đủ điều kiện, bị vô hiệu hóa hoặc không thể xác minh có thể chặn việc đóng vô thời hạn; giới hạn được chấp nhận này không có đóng ép hay cách khắc phục tự động.",
  // Closure request detail
  "Back to closure workflow": "Quay lại quy trình đóng",
  "Loading closure request": "Đang tải yêu cầu đóng",
  "Loading authoritative status…": "Đang tải trạng thái chính thức…",
  "Closure request could not be loaded": "Không thể tải yêu cầu đóng",
  "Closure request metadata is unavailable.":
    "Siêu dữ liệu yêu cầu đóng không khả dụng.",
  "Retry closure request": "Thử tải lại yêu cầu đóng",
  "Refreshing authoritative status": "Đang làm mới trạng thái chính thức",
  "Publication revision after this request: {revision}":
    "Revision của Publication sau yêu cầu này: {revision}",
  "Status could not be refreshed": "Không thể làm mới trạng thái",
  "Previously verified status remains visible; refresh before issuing commands.":
    "Trạng thái đã xác minh trước đó vẫn hiển thị; hãy làm mới trước khi ra lệnh.",
  "Command outcome is uncertain": "Kết quả lệnh chưa xác định",
  "This command may have committed. Refresh reads current authority; it never repeats a mutation. An explicit retry re-sends only the original revision with the same idempotency key, preserving the durable command identity.":
    "Lệnh này có thể đã được cam kết. Làm mới đọc trạng thái chính thức hiện tại; nó không bao giờ lặp lại một thay đổi. Một lần thử lại chủ đích chỉ gửi lại revision gốc với cùng khóa idempotency, giữ nguyên danh tính lệnh bền vững.",
  "Durable request record": "Bản ghi yêu cầu bền vững",
  "Closure request ID (exact)": "Mã yêu cầu đóng (chính xác)",
  "Publication ID": "Mã Publication",
  "Requesting Teacher identity (as returned by the server)":
    "Danh tính Giáo viên yêu cầu (theo máy chủ trả về)",
  "Requested at": "Thời điểm yêu cầu",
  "Finalization started at": "Thời điểm bắt đầu hoàn tất",
  "Not started": "Chưa bắt đầu",
  "Resolved at": "Thời điểm xử lý xong",
  "Not resolved": "Chưa xử lý xong",
  "Authoritative Publication revision": "Revision Publication chính thức",
  "Recipient counts, per-recipient cutoffs and completion percentages are not disclosed by the server for this workflow; none are shown.":
    "Số người nhận, mốc thời gian từng người nhận và phần trăm hoàn tất không được máy chủ tiết lộ cho quy trình này; không hiển thị gì cả.",
  "Bounded progress committed": "Đã cam kết tiến độ giới hạn",
  "The server confirmed the durable CLOSED transition for this Publication.":
    "Máy chủ đã xác nhận chuyển đổi CLOSED bền vững cho Publication này.",
  "This operation key committed its bounded batch (closed=false). Its acknowledgment is immutable; use the request status and a new deliberate command to continue.":
    "Khóa thao tác này đã cam kết lô giới hạn của nó (closed=false). Xác nhận của nó là bất biến; hãy dùng trạng thái yêu cầu và một lệnh chủ đích mới để tiếp tục.",
  "Completion committed the durable CLOSED transition.":
    "Lệnh COMPLETE đã cam kết chuyển đổi CLOSED bền vững.",
  "Committed bounded progress (closed=false). Inspect status; start another batch with a new key when ready.":
    "Đã cam kết tiến độ giới hạn (closed=false). Hãy kiểm tra trạng thái; bắt đầu lô mới với khóa mới khi sẵn sàng.",
  "Cancellation confirmed by the server.": "Máy chủ đã xác nhận việc hủy.",
  "Sending reviewed command…": "Đang gửi lệnh đã xem xét…",
  "The server throttled this operation. Reading authoritative status before an explicit same-key retry.":
    "Máy chủ đã giới hạn thao tác này. Đang đọc trạng thái chính thức trước một lần thử lại chủ đích với cùng khóa.",
  "The command conflicted with server state. Reading authoritative status before another action.":
    "Lệnh xung đột với trạng thái máy chủ. Đang đọc trạng thái chính thức trước thao tác khác.",
  "Authoritative status confirms the command's effect. No mutation was replayed.":
    "Trạng thái chính thức xác nhận hiệu ứng của lệnh. Không có thay đổi nào được gửi lại.",
  "Manage this request": "Quản lý yêu cầu này",
  "COMPLETE reconciles overdue bounded work and processes due confirmed recipients in bounded batches; only the owning Teacher's command can commit the final CLOSED transition. CANCEL is possible only before the first closure-driven finalization.":
    "COMPLETE đối chiếu công việc giới hạn quá hạn và xử lý các người nhận đã xác nhận đến hạn theo lô giới hạn; chỉ lệnh của Giáo viên sở hữu mới có thể cam kết chuyển đổi CLOSED cuối cùng. CANCEL chỉ khả dụng trước lần hoàn tất đầu tiên do quy trình đóng thúc đẩy.",
  "Review and COMPLETE closure": "Xem lại và COMPLETE đóng",
  "Review and CANCEL request": "Xem lại và CANCEL yêu cầu",
  "Cancellation is unavailable because finalization has started.":
    "Không thể hủy vì quá trình hoàn tất đã bắt đầu.",
  "The request is FINALIZING; cancellation can no longer win. Use COMPLETE to continue bounded batches.":
    "Yêu cầu đang FINALIZING; việc hủy không còn hiệu lực. Hãy dùng COMPLETE để tiếp tục các lô giới hạn.",
  "This request is resolved": "Yêu cầu này đã được xử lý xong",
  "Resolved workflow states never return to live states. A replacement, if still needed, is a new request with fresh notices and fresh 48-hour periods.":
    "Các trạng thái đã xử lý không bao giờ quay về trạng thái đang hoạt động. Nếu vẫn cần, thay thế là một yêu cầu mới với thông báo mới và các thời gian 48 giờ mới.",
  "CANCEL closure request": "CANCEL yêu cầu đóng",
  "COMPLETE closure": "COMPLETE đóng",
  "Confirm cancellation": "Xác nhận hủy",
  "Confirm completion command": "Xác nhận lệnh hoàn tất",
  "Command revision: {revision}. The command is sent with this authoritative revision and one durable operation key.":
    "Revision của lệnh: {revision}. Lệnh được gửi với revision chính thức này và một khóa thao tác bền vững.",
  "Cancellation resolves this request while preserving published notices to recipients as non-actionable history. A later replacement is a new request with new notices and fresh 48-hour periods.":
    "Việc hủy xử lý xong yêu cầu này nhưng vẫn giữ các thông báo đã xuất bản tới người nhận như lịch sử không thể hành động. Thay thế sau này là một yêu cầu mới với thông báo mới và các thời gian 48 giờ mới.",
  "Completion processes eligible due work in bounded batches. If confirmed notices or cutoffs are still outstanding, the server answers CLOSURE_NOT_READY without undoing committed progress. The Publication closes only when every Attempt is terminal and every captured member is resolved.":
    "Lệnh COMPLETE xử lý công việc đủ điều kiện đến hạn theo lô giới hạn. Nếu thông báo đã xác nhận hoặc mốc thời gian vẫn chưa xong, máy chủ trả về CLOSURE_NOT_READY mà không hoàn tác tiến độ đã cam kết. Publication chỉ đóng khi mọi Attempt ở trạng thái cuối và mọi thành viên được ghi nhận đã được xử lý.",
  "Type {phrase} to enable this action": "Gõ {phrase} để bật hành động này",
  // Closure model labels/descriptions rendered through localize
  Pending: "Đang chờ",
  Finalizing: "Đang hoàn tất",
  Cancelled: "Đã hủy",
  Completed: "Đã hoàn tất",
  "Resolved early": "Đã xử lý sớm",
  "The closure request is durably recorded. Student notices are prepared and confirmed independently; the Publication is not CLOSED yet.":
    "Yêu cầu đóng đã được ghi bền vững. Các thông báo cho học sinh được chuẩn bị và xác nhận độc lập; Publication chưa CLOSED.",
  "Closure-driven finalization has started. Due confirmed notices have begun producing system submissions; cancellation can no longer win.":
    "Quá trình hoàn tất do quy trình đóng thúc đẩy đã bắt đầu. Các thông báo đã xác nhận đến hạn bắt đầu tạo các lần nộp của hệ thống; việc hủy không còn hiệu lực.",
  "The request was cancelled before any closure-driven finalization. Published notices remain visible to their recipients as non-actionable history.":
    "Yêu cầu đã bị hủy trước mọi lần hoàn tất do quy trình đóng thúc đẩy. Các thông báo đã xuất bản vẫn hiển thị với người nhận như lịch sử không thể hành động.",
  "All Attempts were terminal and all captured members resolved; the owning Teacher's COMPLETE command committed the durable CLOSED transition.":
    "Mọi Attempt đều ở trạng thái cuối và mọi thành viên được ghi nhận đã xử lý xong; lệnh COMPLETE của Giáo viên sở hữu đã cam kết chuyển đổi CLOSED bền vững.",
  "The workflow was resolved without its own cutoff path, together with an ordinary all-terminal Publication closure.":
    "Quy trình được xử lý mà không qua đường mốc thời gian riêng của nó, cùng với một lần đóng Publication thông thường khi mọi Attempt ở trạng thái cuối.",
  // Student notification inbox and detail
  "Student access required": "Cần quyền Học sinh",
  "The assessment inbox belongs to the authenticated recipient with current Student authority.":
    "Hộp thư bài kiểm tra thuộc về người nhận đã xác thực với quyền Học sinh hiện tại.",
  "This notice belongs to its recipient with current Student authority.":
    "Thông báo này thuộc về người nhận của nó với quyền Học sinh hiện tại.",
  "Inbox unavailable": "Hộp thư không khả dụng",
  "Notice unavailable": "Thông báo không khả dụng",
  "Assessment notifications": "Thông báo bài kiểm tra",
  "Your inbox": "Hộp thư của bạn",
  "Durable closure notices addressed to your account. Reading a notice is not an acknowledgment and never starts or changes a 48-hour period; only the server's confirmed accessibility of your notice does.":
    "Các thông báo đóng bền vững gửi đến tài khoản của bạn. Việc đọc một thông báo không phải là xác nhận và không bao giờ bắt đầu hay thay đổi thời gian 48 giờ; chỉ mức độ truy cập đã xác nhận của máy chủ đối với thông báo của bạn mới có tác dụng.",
  "Refreshing inbox": "Đang làm mới hộp thư",
  "Refresh inbox": "Làm mới hộp thư",
  "Inbox could not be read": "Không thể đọc hộp thư",
  "Loading notifications": "Đang tải thông báo",
  "Loading your assessment notices…":
    "Đang tải các thông báo bài kiểm tra của bạn…",
  "No assessment notices yet": "Chưa có thông báo bài kiểm tra nào",
  "When a Teacher requests delayed closure of an assessment you have an active attempt in, a durable notice appears here.":
    "Khi Giáo viên yêu cầu đóng trễ một bài kiểm tra mà bạn có Attempt đang hoạt động, một thông báo bền vững sẽ xuất hiện tại đây.",
  Published: "Thời điểm xuất bản",
  "48-hour period": "Thời gian 48 giờ",
  "Confirmed cutoff (authoritative)": "Mốc kết thúc đã xác nhận (chính thức)",
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
  "Authoritative timeline": "Dòng thời gian chính thức",
  "Notice confirmed": "Thông báo đã xác nhận",
  "48-hour cutoff": "Mốc kết thúc 48 giờ",
  "Server time of this response": "Giờ máy chủ của phản hồi này",
  "(your local time)": "(giờ địa phương của bạn)",
  "The server timestamps above remain authoritative.":
    "Các mốc thời gian máy chủ ở trên vẫn là chính thức.",
  "Your notice is being prepared": "Thông báo của bạn đang được chuẩn bị",
  "The 48-hour period starts only when the server confirms this notice is genuinely accessible for you. Until then there is no cutoff and no countdown; nothing is asked of you yet.":
    "Thời gian 48 giờ chỉ bắt đầu khi máy chủ xác nhận thông báo này thực sự truy cập được với bạn. Cho đến lúc đó không có mốc kết thúc và không có đếm ngược; bạn chưa cần làm gì cả.",
  "Closure cancelled": "Đã hủy quy trình đóng",
  "The Teacher cancelled this closure request. This notice is retained as history and is no longer actionable.":
    "Giáo viên đã hủy yêu cầu đóng này. Thông báo được giữ lại như lịch sử và không còn thể hành động.",
  "Cutoff reached; finalization pending":
    "Đã đến mốc kết thúc; đang chờ hoàn tất",
  "Outcome recorded": "Đã ghi nhận kết quả",
  "You submitted this attempt yourself.": "Chính bạn đã nộp Attempt này.",
  "You abandoned this attempt; it has no grade.":
    "Bạn đã bỏ dở Attempt này; nó không có điểm.",
  "The confirmed cutoff was reached. Refresh to track server finalization of eligible saved answers.":
    "Đã đến mốc kết thúc đã xác nhận. Hãy làm mới để theo dõi việc máy chủ hoàn tất các câu trả lời đã lưu đủ điều kiện.",
  "Your saved answers were submitted and graded by the system.":
    "Các câu trả lời đã lưu của bạn đã được hệ thống nộp và chấm.",
  "This workflow was resolved without a cutoff finalization.":
    "Quy trình này được xử lý mà không có bước hoàn tất theo mốc kết thúc.",
  "A result exists for this attempt; use the Student results experience to view it.":
    "Có một kết quả cho Attempt này; hãy dùng trải nghiệm kết quả của Học sinh để xem.",
  References: "Tham chiếu",
  "Your attempt (authorized)": "Attempt của bạn (đã ủy quyền)",
  "Closure request": "Yêu cầu đóng",
  Publication: "Publication",
  "Result (when finalized)": "Kết quả (khi hoàn tất)",
  "Not available yet": "Chưa có",
  "Opening your attempt or result always revalidates your current access on the server. This notice remains readable as your own history even if you leave the class or the assessment closes.":
    "Việc mở Attempt hoặc kết quả luôn xác minh lại quyền truy cập hiện tại của bạn trên máy chủ. Thông báo này vẫn đọc được như lịch sử của bạn ngay cả khi bạn rời lớp hoặc bài kiểm tra đóng.",
  // Notice status labels rendered through localize
  "Notice preparing": "Đang chuẩn bị thông báo",
  "48-hour period confirmed": "Đã xác nhận thời gian 48 giờ",
  "Cutoff reached": "Đã đến mốc kết thúc",
  "You submitted": "Bạn đã nộp",
  "You abandoned": "Bạn đã bỏ dở",
  "Finalized by system": "Hệ thống đã hoàn tất",
  "Not started — your notice is not confirmed yet":
    "Chưa bắt đầu — thông báo của bạn chưa được xác nhận",
  "No confirmed cutoff was established":
    "Không có mốc kết thúc đã xác nhận nào được thiết lập",
  // Cutoff remaining guidance composed from server timestamps
  "the cutoff has been reached on the server": "máy chủ đã đến mốc kết thúc",
  "at least 48 hours remain (server guidance)":
    "còn ít nhất 48 giờ (theo hướng dẫn của máy chủ)",
  "about {duration} remain (server guidance)":
    "còn khoảng {duration} (theo hướng dẫn của máy chủ)",
  "{count} hour": "{count} giờ",
  "{count} hours": "{count} giờ",
  "{count} minute": "{count} phút",
  "{count} minutes": "{count} phút",
  "less than a minute remains (server guidance)":
    "còn chưa đầy một phút (theo hướng dẫn của máy chủ)",
  "Cutoff confirmed; refresh for current guidance.":
    "Đã xác nhận mốc kết thúc; hãy làm mới để xem hướng dẫn hiện tại.",
  // Teacher results table
  "Publication results": "Kết quả Publication",
  "Attempt results": "Kết quả các Attempt",
  "Owner-authorized Attempt rows with exact totals. ACTIVE and ABANDONED attempts have no result yet. No answers or answer keys are included in this data.":
    "Các dòng Attempt được chủ sở hữu ủy quyền với tổng điểm chính xác. Các Attempt ACTIVE và ABANDONED chưa có kết quả. Không có câu trả lời hay đáp án nào nằm trong dữ liệu này.",
  "Refreshing results": "Đang làm mới kết quả",
  "Refresh results": "Làm mới kết quả",
  "Results could not be read": "Không thể đọc kết quả",
  "Loading results": "Đang tải kết quả",
  "Loading authoritative Attempt rows…":
    "Đang tải các dòng Attempt chính thức…",
  "No attempt rows yet": "Chưa có dòng Attempt nào",
  "No attempts exist for this Publication, or none are visible to your account.":
    "Không có Attempt nào cho Publication này, hoặc không Attempt nào hiển thị với tài khoản của bạn.",
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
  "Attempt IDs are the real authorized identities returned by the server. Each per-attempt monitoring destination performs a fresh server lookup; this results table does not grant monitoring access or discover a global roster.":
    "Các mã Attempt là danh tính thật đã được ủy quyền do máy chủ trả về. Mỗi đích giám sát theo Attempt thực hiện một lần tra cứu máy chủ mới; bảng kết quả này không cấp quyền giám sát hay khám phá danh sách toàn cục.",
  // Attempt state and terminal reason labels rendered through localize
  Active: "Đang hoạt động",
  Submitted: "Đã nộp",
  Abandoned: "Đã bỏ dở",
  "Manual submission": "Nộp thủ công",
  "Bounded timeout": "Hết thời gian giới hạn",
  "Teacher notice cutoff": "Mốc thông báo của Giáo viên",
  // Unified results-first Publication detail (UX-04) and the shared
  // PublicationResultsSection empty state
  "Configuration summary": "Tóm tắt cấu hình",
  "No results yet": "Chưa có kết quả nào",
} as const;
