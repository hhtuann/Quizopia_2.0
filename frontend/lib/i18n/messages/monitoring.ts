// Proctoring monitoring catalog for Student consent/camera surfaces and the
// Teacher monitoring/timeline screens. Keys are the exact rendered English
// source text; values are Vietnamese presentation only. Session/evidence enum
// tokens (ACTIVE/ENDED, CLIENT_OBSERVATION...), identifiers and cleanup
// outcome tokens (succeeded/failed/not-requested...) remain canonical.
// Cross-screen keys already owned by other catalogs are not duplicated here:
// "Student access required", "Publication", "Classroom", "Publication ID",
// "Attempt ID", "Return to application", "Switch to Teaching workspace"
// (assessment) and "Teacher access required" (authoring).
export const monitoringMessages = {
  // Student monitoring screen
  "Learning · Monitored Attempt": "Học tập · Bài làm được giám sát",
  "Activity and camera monitoring": "Giám sát hoạt động và camera",
  "A current Student role and authoritative Attempt ownership are required.":
    "Yêu cầu vai trò Học sinh hiện hành và quyền sở hữu bài làm chính thức.",
  "Invalid Attempt reference": "Tham chiếu bài làm không hợp lệ",
  "This route requires a canonical lowercase Attempt UUID.":
    "Tuyến này yêu cầu UUID bài làm chuẩn viết thường.",
  "Monitoring session active": "Phiên giám sát đang hoạt động",
  "The server verified this bounded CLASS Attempt and returned the bindings shown below. Activity delivery is separate from Assessment SAVE and SUBMIT; a monitoring outage never changes answer-save status.":
    "Máy chủ đã xác minh bài làm CLASS có giới hạn này và trả về các ràng buộc hiển thị bên dưới. Việc gửi hoạt động tách biệt với LƯU và NỘP của Assessment; sự cố giám sát không bao giờ thay đổi trạng thái lưu câu trả lời.",
  Session: "Phiên",
  Deadline: "Hạn chót",
  "End local monitoring connection": "Kết thúc kết nối giám sát cục bộ",
  "Before monitoring begins": "Trước khi giám sát bắt đầu",
  "Quizopia will ask the server to verify that this is your current, active, bounded, monitoring-enabled CLASS Attempt. PUBLIC and durationless Attempts are not eligible.":
    "Quizopia sẽ yêu cầu máy chủ xác minh rằng đây là bài làm CLASS hiện tại, đang hoạt động, có giới hạn thời gian và được bật giám sát. Bài làm PUBLIC hoặc không thời hạn không đủ điều kiện.",
  "Once verified, this page can report its own question-navigation, answer-interaction metadata, visibility, focus, fullscreen, network, duplicate-tab, and camera technical states. It does not collect answer contents, unrelated URLs, browser history, keystrokes, your screen, or microphone.":
    "Sau khi được xác minh, trang này có thể báo cáo điều hướng câu hỏi, siêu dữ liệu tương tác trả lời, trạng thái hiển thị, tiêu điểm, toàn màn hình, mạng, tab trùng lặp và trạng thái kỹ thuật camera của chính nó. Trang không thu thập nội dung câu trả lời, URL không liên quan, lịch sử trình duyệt, phím gõ, màn hình hay micro của bạn.",
  "Browser observations can be incomplete or manipulated and are not proof of misconduct. No event on this page automatically changes a grade, submits an Attempt, or imposes a sanction.":
    "Quan sát từ trình duyệt có thể không đầy đủ hoặc bị can thiệp và không phải bằng chứng vi phạm. Không sự kiện nào trên trang này tự động thay đổi điểm, nộp bài hay áp dụng hình thức kỷ luật.",
  "The server denied access or a required authority/provider feature is unavailable. No activity collection or camera capture is active.":
    "Máy chủ từ chối truy cập hoặc một quyền/tính năng cần thiết chưa khả dụng. Không có hoạt động thu thập hành vi hay camera nào đang bật.",
  "Monitoring ended": "Giám sát đã kết thúc",
  "Local monitoring resources were released. This did not alter the authoritative Attempt.":
    "Tài nguyên giám sát cục bộ đã được giải phóng. Điều này không thay đổi bài làm chính thức.",
  "Verifying monitoring eligibility": "Đang xác minh điều kiện giám sát",
  "Verify eligibility and begin monitoring":
    "Xác minh điều kiện và bắt đầu giám sát",
  "Activity evidence delivery": "Gửi dữ liệu hoạt động",
  "State: {state}. Pending: {pending}. Dropped because the in-memory queue was full: {dropped}. Delivery status never changes Assessment SAVE or SUBMIT.":
    "Trạng thái: {state}. Đang chờ: {pending}. Bị bỏ vì hàng đợi trong bộ nhớ đã đầy: {dropped}. Trạng thái gửi không bao giờ thay đổi LƯU hay NỘP của Assessment.",
  // Student camera
  "Student camera": "Camera học sinh",
  "Share your camera deliberately": "Chủ động chia sẻ camera của bạn",
  "Your authorized Teacher may view this camera while the eligible monitored Attempt is active. Quizopia does not request your microphone, screen, unrelated tab URLs, keyboard input, or browser history. Camera permission alone is not a disciplinary decision or proof of misconduct.":
    "Giáo viên được cấp quyền có thể xem camera này khi bài làm được giám sát đủ điều kiện còn đang hoạt động. Quizopia không yêu cầu micro, màn hình, URL tab không liên quan, phím gõ hay lịch sử trình duyệt của bạn. Quyền camera tự thân nó không phải quyết định kỷ luật hay bằng chứng vi phạm.",
  "Your camera preview": "Xem trước camera của bạn",
  "Live camera publishing": "Đang phát camera trực tiếp",
  "Camera not live": "Camera chưa phát",
  "Request camera and connect": "Yêu cầu camera và kết nối",
  "Reauthorize and reconnect": "Cấp quyền lại và kết nối lại",
  "Stop camera": "Dừng camera",
  "Cleanup results": "Kết quả dọn dẹp",
  "Local track: {local}. SDK disconnect: {sdk}. Backend revoke: {backend}. A backend revoke result records local intent/state only; hard provider revocation is not claimed.":
    "Track cục bộ: {local}. Ngắt SDK: {sdk}. Thu hồi backend: {backend}. Kết quả thu hồi backend chỉ ghi nhận ý định/trạng thái cục bộ; không tuyên bố đã thu hồi cứng phía nhà cung cấp.",
  "Camera is off": "Camera đang tắt",
  "Nothing is captured until you choose to request camera access.":
    "Chưa có gì được ghi lại cho đến khi bạn chọn yêu cầu quyền camera.",
  "Waiting for browser permission": "Đang chờ quyền của trình duyệt",
  "Use the browser prompt to allow or deny this camera request.":
    "Dùng hộp thoại của trình duyệt để cho phép hoặc từ chối yêu cầu camera này.",
  Connecting: "Đang kết nối",
  "A scoped credential was issued; the camera is not live until publishing succeeds.":
    "Thông tin xác thực phạm vi hẹp đã được cấp; camera chưa phát cho đến khi phát trực tiếp thành công.",
  "Camera is live": "Camera đang phát",
  "The SDK is connected and your camera track is published. No microphone is published.":
    "SDK đã kết nối và track camera của bạn đã được phát. Không phát micro.",
  "Fresh authorization required": "Cần cấp quyền mới",
  "The connection changed. Disconnect and backend revoke cleanup are in progress before Quizopia can request a fresh credential.":
    "Kết nối đã thay đổi. Việc ngắt kết nối và dọn dẹp thu hồi backend đang diễn ra trước khi Quizopia có thể yêu cầu thông tin xác thực mới.",
  "Camera permission denied": "Quyền camera bị từ chối",
  "No camera is active. The academic outcome for refusal is not defined by this interface.":
    "Không có camera nào hoạt động. Hệ quả học thuật khi từ chối không do giao diện này quyết định.",
  "No camera found": "Không tìm thấy camera",
  "No camera is active. Connect a camera or contact your Teacher about accommodations.":
    "Không có camera nào hoạt động. Hãy kết nối camera hoặc liên hệ giáo viên về sự sắp xếp phù hợp.",
  "Camera stopped or failed": "Camera đã dừng hoặc lỗi",
  "The device could not provide a usable video track. No automatic penalty was applied.":
    "Thiết bị không thể cung cấp track video khả dụng. Không có hình thức phạt tự động nào được áp dụng.",
  "Monitoring media unavailable": "Phương tiện giám sát không khả dụng",
  "Authorization, provider qualification, or the media service is unavailable. The camera is not active.":
    "Quyền, điều kiện nhà cung cấp hoặc dịch vụ phương tiện không khả dụng. Camera không hoạt động.",
  "Camera disconnected": "Camera đã ngắt kết nối",
  "Local cleanup finished. See the separate SDK and backend outcomes below; reauthorization is required before reconnecting.":
    "Dọn dẹp cục bộ đã xong. Xem các kết quả SDK và backend riêng bên dưới; cần cấp quyền lại trước khi kết nối lại.",
  "Stopping camera": "Đang dừng camera",
  "The local track, SDK connection, and backend grant are being cleaned up separately.":
    "Track cục bộ, kết nối SDK và quyền backend đang được dọn dẹp riêng biệt.",
  // Teacher monitoring screen
  "Teaching · Monitoring": "Giảng dạy · Giám sát",
  "Authorized Attempt monitoring": "Giám sát bài làm được cấp quyền",
  "The server requires a current Teacher role, Publication ownership, and Classroom authority. Knowing an Attempt ID is not authorization.":
    "Máy chủ yêu cầu vai trò Giáo viên hiện hành, quyền sở hữu bài kiểm tra và quyền lớp học. Biết mã bài làm không đồng nghĩa với được cấp quyền.",
  "Workspace selection only changes presentation; server authorization remains mandatory.":
    "Việc chọn không gian làm việc chỉ thay đổi hiển thị; thẩm định của máy chủ vẫn bắt buộc.",
  "Open a known Attempt": "Mở bài làm đã biết",
  "No global monitoring roster API exists. Enter an exact Attempt ID from the authorized Teacher Results experience; the backend performs a fresh resource check before revealing whether a session exists.":
    "Không có API danh sách giám sát toàn cục. Nhập chính xác mã bài làm từ trải nghiệm Kết quả dành cho giáo viên được cấp quyền; hệ thống phía sau kiểm tra tài nguyên mới trước khi tiết lộ có phiên hay không.",
  "Enter a canonical lowercase Attempt UUID.":
    "Nhập UUID bài làm chuẩn viết thường.",
  "Check authorization": "Kiểm tra quyền",
  "Checking current authority": "Đang kiểm tra quyền hiện hành",
  "Loading the authorized session and first timeline page.":
    "Đang tải phiên được cấp quyền và trang đầu của dòng thời gian.",
  "Session not accessible": "Không thể truy cập phiên",
  "No authorized monitoring session is available for that Attempt. The response intentionally does not reveal whether a foreign resource exists.":
    "Không có phiên giám sát được cấp quyền cho bài làm đó. Phản hồi cố ý không tiết lộ tài nguyên của người khác có tồn tại hay không.",
  "Current authority or a required service could not be verified. Access fails closed and no cached grant is used.":
    "Không xác minh được quyền hiện hành hoặc dịch vụ cần thiết. Truy cập mặc định bị chặn và không dùng quyền đã lưu trong bộ nhớ.",
  "Session status": "Trạng thái phiên",
  "Activity timeline": "Dòng thời gian hoạt động",
  "Server ordinal controls ordering. Source labels distinguish browser reports from Assessment persistence facts.":
    "Thứ tự máy chủ quyết định thứ tự hiển thị. Nhãn nguồn phân biệt báo cáo trình duyệt với dữ liệu Assessment đã lưu.",
  "Refresh authority and timeline": "Làm mới quyền và dòng thời gian",
  "Loading more evidence": "Đang tải thêm dữ liệu",
  // Teacher camera
  "Authorized live camera": "Camera trực tiếp được cấp quyền",
  "Student camera stream": "Luồng camera học sinh",
  "This view requests a subscribe-only Teacher grant for this exact server-authorized session. It cannot publish your camera or microphone, administer the room, or discover other sessions.":
    "Chế độ xem này yêu cầu quyền Giáo viên chỉ đăng ký cho chính phiên này do máy chủ cấp. Nó không thể phát camera hay micro của bạn, quản lý phòng hay dò tìm phiên khác.",
  "Authorized Student camera": "Camera học sinh được cấp quyền",
  "Camera visible": "Đang thấy camera",
  "No live camera": "Không có camera trực tiếp",
  "Live subscription": "Đăng ký trực tiếp",
  "Camera status": "Trạng thái camera",
  "No media grant has been requested.": "Chưa yêu cầu quyền phương tiện nào.",
  "Checking fresh Teacher and target-Student authority.":
    "Đang kiểm tra quyền Giáo viên và quyền của Học sinh đích.",
  "Authorized subscription connected, but no Student camera track is available.":
    "Đăng ký được cấp quyền đã kết nối, nhưng chưa có track camera của Học sinh.",
  "An authorized Student camera track is currently subscribed.":
    "Một track camera Học sinh được cấp quyền đang được đăng ký.",
  "SDK disconnect and backend revoke intent are in progress. Outcomes are reported separately when they settle.":
    "Ngắt SDK và ý định thu hồi backend đang diễn ra. Kết quả được báo cáo riêng khi hoàn tất.",
  "The connection changed. Disconnect and backend revoke cleanup are in progress before fresh authorization.":
    "Kết nối đã thay đổi. Ngắt kết nối và dọn dẹp thu hồi backend đang diễn ra trước khi cấp quyền mới.",
  "Local cleanup finished. Separate SDK disconnect and backend revoke outcomes appear below.":
    "Dọn dẹp cục bộ đã xong. Kết quả ngắt SDK và thu hồi backend riêng biệt hiển thị bên dưới.",
  "Live media is unavailable or not authorized. No camera is being shown.":
    "Phương tiện trực tiếp không khả dụng hoặc không được cấp quyền. Không hiển thị camera nào.",
  "SDK disconnect: {sdk}. Backend revoke: {backend}. A backend result does not prove hard provider revocation.":
    "Ngắt SDK: {sdk}. Thu hồi backend: {backend}. Kết quả backend không chứng minh đã thu hồi cứng phía nhà cung cấp.",
  "Authorize camera view": "Cấp quyền xem camera",
  "Reauthorize subscription": "Cấp quyền lại cho đăng ký",
  "Disconnect view": "Ngắt chế độ xem",
  // Teacher timeline
  "No monitoring evidence is available for this authorized session yet.":
    "Chưa có dữ liệu giám sát nào cho phiên được cấp quyền này.",
  "Monitoring activity timeline": "Dòng thời gian hoạt động giám sát",
  "Question {number}": "Câu hỏi {number}",
  "Assessment revision {revision}": "Phiên bản Assessment {revision}",
  "Browser observations can be incomplete or manipulated. Persistence facts mean Assessment stored metadata; neither proves misconduct.":
    "Quan sát từ trình duyệt có thể không đầy đủ hoặc bị can thiệp. Dữ liệu đã lưu có nghĩa là Assessment lưu siêu dữ liệu; không cái nào chứng minh vi phạm.",
  "Observed/occurred: {value}": "Quan sát/xảy ra: {value}",
  "not reported": "không được báo cáo",
  "Received: {value}": "Đã nhận: {value}",
  // Timeline evidence type labels (describeEvidenceType localize targets)
  "Question opened": "Mở câu hỏi",
  "Question left": "Rời câu hỏi",
  "Answer selected (browser report)": "Chọn đáp án (báo cáo trình duyệt)",
  "Answer changed (browser report)": "Đổi đáp án (báo cáo trình duyệt)",
  "Answer cleared (browser report)": "Xóa đáp án (báo cáo trình duyệt)",
  "Tab hidden": "Tab bị ẩn",
  "Tab visible": "Tab hiển thị",
  "Window lost focus": "Cửa sổ mất tiêu điểm",
  "Window focused": "Cửa sổ có tiêu điểm",
  "Fullscreen entered": "Vào chế độ toàn màn hình",
  "Fullscreen exited": "Thoát chế độ toàn màn hình",
  "Camera permission granted": "Đã cấp quyền camera",
  "No camera device found": "Không tìm thấy thiết bị camera",
  "Camera device error": "Lỗi thiết bị camera",
  "Camera unavailable": "Camera không khả dụng",
  "Media disconnected": "Đã ngắt kết nối phương tiện",
  Reconnecting: "Đang kết nối lại",
  "Network disconnected": "Mất kết nối mạng",
  "Network reconnected": "Đã kết nối lại mạng",
  "Duplicate attempt tab detected": "Phát hiện tab bài làm trùng lặp",
  "Monitoring session started": "Bắt đầu phiên giám sát",
  "Monitoring session ended": "Kết thúc phiên giám sát",
  "Answer persisted (Assessment fact)":
    "Đã lưu câu trả lời (dữ liệu Assessment)",
  // Provenance labels (PROVENANCE_LABELS localize targets)
  "Browser observation": "Quan sát trình duyệt",
  "Server session fact": "Dữ liệu phiên máy chủ",
  "Assessment persistence fact": "Dữ liệu Assessment đã lưu",
} as const;
