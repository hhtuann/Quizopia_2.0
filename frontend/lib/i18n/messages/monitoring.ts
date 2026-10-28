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
  "A current Student role and ownership of the attempt are required.":
    "Yêu cầu vai trò Học sinh hiện hành và quyền sở hữu bài làm.",
  "Invalid Attempt reference": "Tham chiếu bài làm không hợp lệ",
  "This link is not a valid attempt address.":
    "Liên kết này không phải địa chỉ bài làm hợp lệ.",
  "Monitoring session active": "Phiên giám sát đang hoạt động",
  "Monitoring is verified for this class attempt. Sending monitoring data is separate from saving and submitting; a monitoring problem never changes your saved answers.":
    "Đã xác minh giám sát cho bài làm lớp này. Việc gửi dữ liệu giám sát tách biệt với lưu và nộp bài; sự cố giám sát không bao giờ làm thay đổi câu trả lời đã lưu.",
  Session: "Phiên",
  Deadline: "Hạn chót",
  "End monitoring": "Kết thúc giám sát",
  "Before monitoring begins": "Trước khi giám sát bắt đầu",
  "Quizopia will verify this is your current, time-limited, monitored class attempt. PUBLIC and untimed attempts are not eligible.":
    "Quizopia sẽ xác minh đây là bài làm lớp hiện tại, có giới hạn thời gian và được giám sát của bạn. Bài PUBLIC hoặc không thời hạn không đủ điều kiện.",
  "When verified, this page reports question navigation, answer interactions, tab visibility and focus, fullscreen, network, duplicate tabs, and camera status. It does not collect answer contents, unrelated URLs, browser history, keystrokes, your screen, or microphone.":
    "Khi được xác minh, trang này báo cáo điều hướng câu hỏi, tương tác trả lời, trạng thái hiển thị và tiêu điểm của tab, toàn màn hình, mạng, tab trùng lặp và trạng thái camera. Trang không thu thập nội dung câu trả lời, URL không liên quan, lịch sử trình duyệt, phím gõ, màn hình hay micro của bạn.",
  "Browser observations can be incomplete and are not proof of misconduct. Nothing on this page automatically changes a grade or imposes a sanction.":
    "Quan sát từ trình duyệt có thể không đầy đủ và không phải bằng chứng vi phạm. Không có gì trên trang này tự động thay đổi điểm hay áp đặt hình thức kỷ luật.",
  "Access was denied or a required feature is unavailable. Nothing is being recorded.":
    "Truy cập bị từ chối hoặc một tính năng cần thiết chưa khả dụng. Không ghi hình hay ghi hoạt động nào.",
  "Monitoring ended": "Giám sát đã kết thúc",
  "Monitoring was stopped. Your attempt was not changed.":
    "Đã dừng giám sát. Bài làm của bạn không bị thay đổi.",
  "Verifying monitoring eligibility": "Đang xác minh điều kiện giám sát",
  "Verify eligibility and begin monitoring":
    "Xác minh điều kiện và bắt đầu giám sát",
  "Activity evidence delivery": "Gửi dữ liệu hoạt động",
  "State: {state}. Pending: {pending}. Dropped: {dropped}. Delivery never affects saving or submitting.":
    "Trạng thái: {state}. Đang chờ: {pending}. Bị bỏ: {dropped}. Việc gửi dữ liệu không ảnh hưởng lưu hay nộp bài.",
  // Student camera
  "Student camera": "Camera học sinh",
  "Share your camera deliberately": "Chủ động chia sẻ camera của bạn",
  "Your teacher may view this camera while the monitored attempt is active. Quizopia does not request your microphone, screen, unrelated tab URLs, keyboard input, or browser history. Allowing the camera is not, by itself, a disciplinary decision or proof of wrongdoing.":
    "Giáo viên của bạn có thể xem camera này khi bài làm được giám sát còn đang diễn ra. Quizopia không yêu cầu micro, màn hình, URL tab không liên quan, phím gõ hay lịch sử trình duyệt của bạn. Việc cho phép camera tự thân không phải quyết định kỷ luật hay bằng chứng vi phạm.",
  "Your camera preview": "Xem trước camera của bạn",
  "Live camera publishing": "Đang phát camera trực tiếp",
  "Camera not live": "Camera chưa phát",
  "Request camera and connect": "Yêu cầu camera và kết nối",
  "Reauthorize and reconnect": "Cấp quyền lại và kết nối lại",
  "Stop camera": "Dừng camera",
  "Cleanup results": "Kết quả dọn dẹp",
  "Camera stopped: {local}. Connection closed: {sdk}. Access revocation: {backend}.":
    "Đã dừng camera: {local}. Đã ngắt kết nối: {sdk}. Thu hồi quyền truy cập: {backend}.",
  "Camera is off": "Camera đang tắt",
  "Nothing is captured until you choose to request camera access.":
    "Chưa có gì được ghi lại cho đến khi bạn chọn yêu cầu quyền camera.",
  "Waiting for browser permission": "Đang chờ quyền của trình duyệt",
  "Use the browser prompt to allow or deny this camera request.":
    "Dùng hộp thoại của trình duyệt để cho phép hoặc từ chối yêu cầu camera này.",
  Connecting: "Đang kết nối",
  "Connecting. The camera is not live yet.": "Đang kết nối. Camera chưa phát.",
  "Camera is live": "Camera đang phát",
  "Your camera is being shared. Your microphone is never shared.":
    "Camera của bạn đang được chia sẻ. Micro của bạn không bao giờ được chia sẻ.",
  "Fresh authorization required": "Cần cấp quyền mới",
  "The connection changed. Quizopia is cleaning up before requesting fresh access.":
    "Kết nối đã thay đổi. Quizopia đang dọn dẹp trước khi yêu cầu cấp quyền mới.",
  "Camera permission denied": "Quyền camera bị từ chối",
  "No camera is active. Refusing the camera does not automatically affect your result.":
    "Không có camera nào hoạt động. Từ chối camera không tự động ảnh hưởng đến kết quả của bạn.",
  "No camera found": "Không tìm thấy camera",
  "No camera is active. Connect a camera or contact your Teacher about accommodations.":
    "Không có camera nào hoạt động. Hãy kết nối camera hoặc liên hệ giáo viên về sự sắp xếp phù hợp.",
  "Camera stopped or failed": "Camera đã dừng hoặc lỗi",
  "The camera could not provide video. No automatic penalty was applied.":
    "Camera không cung cấp được hình ảnh. Không có hình thức phạt tự động nào được áp dụng.",
  "Monitoring media unavailable": "Phương tiện giám sát không khả dụng",
  "Camera access is temporarily unavailable. The camera is not active.":
    "Tạm thời không truy cập được camera. Camera không hoạt động.",
  "Camera disconnected": "Camera đã ngắt kết nối",
  "Cleanup finished — see details below. Reconnect requires fresh permission.":
    "Đã dọn dẹp xong — xem chi tiết bên dưới. Cần cấp quyền lại trước khi kết nối lại.",
  "Stopping camera": "Đang dừng camera",
  "The camera, its connection, and its access grant are being cleaned up.":
    "Camera, kết nối và quyền truy cập của nó đang được dọn dẹp.",
  // Teacher monitoring screen
  "Teaching · Monitoring": "Giảng dạy · Giám sát",
  "Authorized Attempt monitoring": "Giám sát bài làm được cấp quyền",
  "Requires a Teacher role plus ownership of the assessment and class. Knowing an Attempt ID alone is not enough.":
    "Cần vai trò Giáo viên cùng quyền sở hữu bài kiểm tra và lớp học. Chỉ biết mã Attempt là chưa đủ.",
  "Switching workspace only changes what you see; access is still verified.":
    "Chuyển không gian làm việc chỉ thay đổi hiển thị; quyền truy cập vẫn được kiểm tra.",
  "Open a known Attempt": "Mở bài làm đã biết",
  "There is no class-wide monitoring list. Paste an Attempt ID from your results table; access is re-checked before anything is shown.":
    "Không có danh sách giám sát theo lớp. Dán mã Attempt từ bảng kết quả của bạn; quyền được kiểm tra lại trước khi hiển thị.",
  "Enter the Attempt ID exactly as copied.":
    "Nhập chính xác mã Attempt như đã sao chép.",
  "Check authorization": "Kiểm tra quyền",
  "Checking current authority": "Đang kiểm tra quyền hiện hành",
  "Loading the session and first timeline page.":
    "Đang tải phiên và trang đầu của dòng thời gian.",
  "Session not accessible": "Không thể truy cập phiên",
  "No monitoring session is available for that attempt.":
    "Không có phiên giám sát nào cho bài làm đó.",
  "Your access could not be verified right now. Nothing is shown.":
    "Tạm thời không xác minh được quyền của bạn. Không hiển thị gì.",
  "Session status": "Trạng thái phiên",
  "Activity timeline": "Dòng thời gian hoạt động",
  "Newest events have higher numbers. Labels distinguish browser reports from saved assessment facts.":
    "Sự kiện mới có số thứ tự lớn hơn. Nhãn phân biệt báo cáo trình duyệt với dữ liệu đã lưu.",
  "Refresh access and timeline": "Làm mới quyền và dòng thời gian",
  "Loading more evidence": "Đang tải thêm dữ liệu",
  // Teacher camera
  "Live monitoring": "Giám sát trực tiếp",
  "Student camera stream": "Luồng camera học sinh",
  "This view can only watch this one session. It cannot use your camera or microphone, control the room, or find other sessions.":
    "Chế độ xem này chỉ có thể theo dõi đúng phiên này. Nó không thể dùng camera hay micro của bạn, điều khiển phòng hay dò tìm phiên khác.",
  "Authorized Student camera": "Camera học sinh được cấp quyền",
  "Camera visible": "Đang thấy camera",
  "No live camera": "Không có camera trực tiếp",
  "Live subscription": "Đăng ký trực tiếp",
  "Camera status": "Trạng thái camera",
  "No camera access has been requested yet.":
    "Chưa yêu cầu quyền xem camera nào.",
  "Checking current access for you and the student.":
    "Đang kiểm tra quyền hiện tại của bạn và của học sinh.",
  "Connected, but the student's camera is not available.":
    "Đã kết nối, nhưng camera của học sinh không khả dụng.",
  "You are viewing the student's camera.": "Bạn đang xem camera của học sinh.",
  "Disconnecting. Results are reported below when finished.":
    "Đang ngắt kết nối. Kết quả sẽ hiển thị bên dưới khi hoàn tất.",
  "The connection changed. Cleaning up before requesting fresh access.":
    "Kết nối đã thay đổi. Đang dọn dẹp trước khi yêu cầu cấp quyền mới.",
  "Cleanup finished — see the results below.":
    "Đã dọn dẹp xong — xem kết quả bên dưới.",
  "The camera is unavailable or not authorized. Nothing is being shown.":
    "Camera không khả dụng hoặc không được cấp quyền. Không hiển thị gì.",
  "Connection closed: {sdk}. Access revocation: {backend} (records intent only).":
    "Đã ngắt kết nối: {sdk}. Thu hồi quyền truy cập: {backend} (chỉ ghi nhận ý định).",
  "Authorize camera view": "Cấp quyền xem camera",
  "Reauthorize subscription": "Cấp quyền lại cho đăng ký",
  "Disconnect view": "Ngắt chế độ xem",
  // Teacher timeline
  "No monitoring events yet for this session.":
    "Chưa có sự kiện giám sát nào cho phiên này.",
  "Monitoring activity timeline": "Dòng thời gian hoạt động giám sát",
  "Question {number}": "Câu hỏi {number}",
  "Assessment revision {revision}": "Phiên bản Assessment {revision}",
  "Observations can be incomplete or manipulated, and saved facts only show what was stored — neither proves misconduct.":
    "Quan sát có thể không đầy đủ hoặc bị can thiệp; dữ liệu đã lưu chỉ cho thấy cái gì được lưu — không cái nào chứng minh vi phạm.",
  "Observed: {value}": "Quan sát: {value}",
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
  "Recorded session fact": "Sự kiện phiên được ghi lại",
  "Saved assessment fact": "Dữ liệu Assessment đã lưu",
} as const;
