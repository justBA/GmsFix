# GMS Fix

App Android giúp **thông báo (FCM) không bị HyperOS chặn**: không bị mất tin, không bị trễ, không bị ngắt kết nối. Viết cho Xiaomi chạy **HyperOS ROM CN** và **không root**.

- Package: `com.anb.gmsfix` · versionName 3.0.x · minSdk 24 · targetSdk 35
- Máy thử nghiệm: Xiaomi `popsicle` (2509FPN0BC), HyperOS OS4.0 CN, Android 17, không root
- Ràng buộc: bình thường **Developer options phải tắt** (để app ngân hàng và VNeID chạy được). Shizuku chỉ bật khi cần bấm **Fix ngay**.

> ☕ App giúp được bạn? [Mời mình ly cà phê](#-mời-mình-ly-cà-phê) nha!

---

## Vì sao thông báo bị mất hoặc trễ trên HyperOS CN

| Nguyên nhân | Hậu quả |
|---|---|
| **Greezer** (bộ đóng băng của `miui-services`) đóng băng app nền, và đóng băng cả GMS (GMS nằm cứng trong `greezer_power_black_list`) | Mất kết nối FCM, tin tới app bị giữ lại |
| **Sleep mode** ban đêm (~01:00–05:30, chỉ ROM CN) đóng băng cả app trong danh sách No Restrictions | Mất tin ban đêm. Muốn sửa tận gốc thì cần root |
| **Deep doze rất gắt**: tắt màn hình là Quick Doze, ngủ sâu sau ~1 phút, khoảng thức gom tin 1 → 6 giờ | Tin ưu tiên thường bị máy chủ giữ lại tới 1–6 giờ |
| **Vuốt app khỏi đa nhiệm = force-stop** (`SwipeUpClean`, `OneKeyClean`, `GameClean`) | App bị dừng hẳn, GMS bỏ tin (`Failed to broadcast to stopped app`) cho tới khi mở lại app |
| **PowerKeeper** ghi đè `MILLET_NO_RESTRICT_APP` từ cài đặt pin mỗi khi khởi động lại hoặc đổi chế độ pin | Các thiết lập ghi bằng lệnh bị mất |
| Cờ ROM `cn.google.services` | GMS tắt một số tính năng: Timeline của Maps, Quick Share, Find Hub ngoại tuyến, chia sẻ vị trí… Không sửa được nếu không root |

---

## Tính năng

Màn hình chính có 2 hướng xử lý, mỗi hướng một màn hình riêng.

### 1. Giữ FCM sống (dùng hằng ngày)

**Danh sách app, chia 2 mức** (bấm ⚡ để đổi mức; app này mở thẳng trang *Battery details* của app được chọn):

| | ⚡ cam **Luôn chạy nền** | ⚡ xám **Chỉ khi có tin** (mặc định) |
|---|---|---|
| Ví dụ | Google Play services, Zepp (đồng hồ) | Facebook, Messenger, Zalo, MoMo, ngân hàng… |
| Cài đặt pin (PowerKeeper) | **No restrictions** | **Battery saver (recommended)** |
| Greezer đóng băng khi rảnh | Không | Có (đỡ pin) |
| Có tin FCM thì được mở băng | ✅ | ✅ |
| Tự khởi chạy, bỏ trạng thái dừng, chạy nền, dữ liệu nền | ✅ | ✅ |
| Miễn Doze, low-latency, standby bucket "active" | ✅ | ❌ (tin ưu tiên cao vẫn được Android cho thức tạm) |

**Tuỳ chọn:**
- **Đánh thức GMS định kỳ** (5/10/15/20 phút): chỉ chạy khi dùng pin và tắt màn hình. App truy vấn provider của GMS để Greezer mở băng GMS, kiểm tra lại kết nối FCM, nếu hỏng thì thử lại sau 3 phút.
- **Cho FCM đánh thức app bị đóng băng**: ghi mục `broadcastctrl:true#pkg/com.google.android.c2dm.intent.RECEIVE#…` vào Settings.Global `aurogon_enable`. Greezer chỉ mở băng app khi có tin FCM. Chế độ "mọi broadcast" (`broadcastctrl:false`) cũng có, nhưng tốn pin hơn.
  - **Tự ghi lại nếu bị đổi**: cloud hoặc HyperOS đổi lại thì app tự ghi lại.
  - **Mọi app có FCM** (mặc định bật): ngoài danh sách đã chọn, thêm mọi app có receiver c2dm. App mới cài tự được thêm. Bấm "xem ›" để xem danh sách.
- **Nới deep doze**: Settings.Global `device_idle_constants`. Ngủ sâu sau 2 phút, thức gom tin mỗi 15 → 30 phút. Tin ưu tiên thường trễ tối đa ~30 phút, thay vì 1–6 giờ. Nút ⓘ cho xem giá trị đang dùng và giá trị mặc định của HyperOS.

**Cảnh báo** (mỗi loại bật/tắt riêng, áp dụng cho app trong danh sách và mọi app có FCM):
- **Mất thông báo**: GMS bỏ tin vì app đang bị dừng.
- **Thông báo bị trễ**: tin ưu tiên cao trễ hơn 2 phút. Tin ưu tiên thường chỉ ghi vào log khi trễ hơn 35 phút.
- **Cài đặt bị đổi**: HyperOS đổi lại các quyền chạy nền.
- **App tắt thông báo**.
- Trang **Log** riêng, có lọc theo loại và ghi rõ tên app.

**Trạng thái:** kết nối FCM, thống kê 24 giờ (số lần rớt, tin trễ, lâu nhất), kiểm tra cài đặt, Shizuku, root, Developer options. Có widget hiển thị trạng thái FCM.

**Fix ngay** (cần Shizuku hoặc root). Các bước:
1. No Restrictions theo 2 mức
2. Tắt GMS freezer của Greezer (`dumpsys greezer IM GMS disable`) và allowlist LM
3. Low-latency
4. Doze whitelist (thêm cho app luôn chạy nền, gỡ cho app chỉ khi có tin)
5. Cho phép chạy nền
6. Dữ liệu nền
7. Standby bucket
8. Tự khởi chạy (MIUI appop 10008)
9. Cấp quyền cho GMS Fix (`WRITE_SECURE_SETTINGS`, `DUMP`, usage access, exact alarm)
10. **Bỏ trạng thái dừng** cho app trong danh sách và mọi app có FCM
11. Kiểm tra lại

Xong thì app tự tắt Developer options, nếu tuỳ chọn đó đang bật.

**Sau khi khởi động lại máy:** app tự áp dụng lại phần Greezer (bằng quyền `DUMP`), `aurogon_enable` và doze, **không cần Shizuku**. Riêng Google Play services bị PowerKeeper xoá khỏi No Restrictions, nên app sẽ nhắc bấm Fix.

### 2. Chỉnh bằng theme (thử nghiệm)

Tạo file `.mtz` ẩn (`Download/GmsFix-FCM.mtz`) để ghi đè `theme_values.xml` của `android.miui`: bỏ GMS khỏi danh sách đen của Greezer, thêm app vào các whitelist. Có nút gỡ hoặc khôi phục app Themes (tuỳ chọn, có rollback).

**Hiện bị chặn:** app Themes (cả bản CN 11.5.6 lẫn bản Global) kiểm tra DRM khi áp dụng theme (`DRM_ERROR_RIGHT_FILE_NOT_EXISTS`). Dự án này **không** vượt DRM.

---

## Những điều đã kiểm chứng trên máy

- `MILLET_NO_RESTRICT_APP` (Settings.System) **chỉ là bản sao** của cài đặt pin trong PowerKeeper. App thường không ghi được khoá này ("You cannot keep your settings in the secure settings"), phải dùng shell.
- Dữ liệu của PowerKeeper được bảo vệ bằng quyền `signature|privileged`, không đọc được. Trang *Battery details* mở được bằng `android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` + `package:<pkg>`.
- `device_config put device_idle …` bị Android 17 chặn, kể cả với shell. Settings.Global `device_idle_constants` thì **ghi được bằng quyền của app**, nhưng app không đọc lại được. Vì vậy app kiểm tra giá trị qua `dumpsys deviceidle`.
- Quick Doze bỏ qua `inactive_to` và motion. `quick_doze_delay_to` có tác dụng nhưng bị giới hạn ~2 phút. `idle_to`/`max_idle_to` có tác dụng.
- Shizuku 13.6 trên ROM này **không lưu** lựa chọn "Allow all the time", nên mỗi lần Fix có thể phải bấm Allow lại.
- Tự khởi chạy **không** cứu được app đã bị dừng hẳn, vì GMS tự bỏ tin trước. Nó chỉ cần cho app bị giết tiến trình nhưng không bị force-stop.

---

## Khuyến nghị khi dùng

- **Đừng vuốt app chat hoặc ngân hàng khỏi đa nhiệm**, đừng bấm "dọn tất cả". Trên ROM này hai thao tác đó là force-stop. Có thể khoá app trong đa nhiệm.
- Thấy cảnh báo **Mất thông báo** thì mở app đó lên một lần, hoặc bấm **Fix ngay**.
- App ở mức "Chỉ khi có tin" thì để cài đặt pin là **Battery saver (recommended)**. Nếu không, PowerKeeper sẽ thêm lại app vào No Restrictions.

---

## Build và cài đặt

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- APK được ký bằng debug key của máy build. Cài đè từ máy khác sẽ lỗi chữ ký, phải gỡ app trước.
- Lần đầu cần chạy **Fix ngay** với Shizuku (hoặc root) để app được cấp `WRITE_SECURE_SETTINGS` và `DUMP`.

## Cấu trúc mã nguồn

| File | Vai trò |
|---|---|
| `HomeActivity` | Màn hình chính, chọn 1 trong 2 tính năng |
| `MainActivity` | Màn hình "Giữ FCM sống": trạng thái, danh sách app, tuỳ chọn, cảnh báo, Fix |
| `Steps` | Các bước shell của Fix |
| `Shell` | Chạy lệnh trực tiếp, qua Shizuku hoặc root |
| `NoRestrictGuard` | 2 mức, `MILLET_NO_RESTRICT_APP`, mở trang cài đặt pin |
| `BroadcastCtrl` | `aurogon_enable` / `broadcastctrl`, danh sách mọi app có FCM |
| `DozeTune` | `device_idle_constants` (nới deep doze) |
| `KeepAlive` | Báo thức đánh thức GMS định kỳ, tự áp dụng lại cài đặt |
| `BootReceiver` | Áp dụng lại sau khi khởi động máy |
| `FcmStatus` | Đọc `dumpsys GcmService`: kết nối, thống kê 24 giờ |
| `HealthCheck` | Kiểm tra cài đặt từng app |
| `AlertCenter`, `AlertLogActivity` | Cảnh báo và trang log |
| `AppGroups`, `AppPicker` | Nhóm app, chọn app |
| `ThemeFix`, `ThemeActivity` | Tạo theme `.mtz` |
| `FcmWidget` | Widget trạng thái FCM |

## Rollback

Dự án dùng git local. Mỗi tính năng là một commit:

```bash
git log --oneline          # xem các mốc
git checkout <commit> -- . # lấy lại mã nguồn ở một mốc
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Mốc `cd2e715` là bản ổn định trước khi chia 2 mức.

---

## ☕ Mời mình ly cà phê

Nếu GMS Fix giúp bạn không còn lỡ tin nhắn, có thể mời mình ly cà phê để mình có động lực làm tiếp 🙏

<p align="center">
  <img src="docs/donate-acb.png" alt="QR chuyển khoản ACB" width="280">
</p>

| Ngân hàng | Chủ tài khoản | Số tài khoản |
|---|---|---|
| **ACB** | NGUYEN BINH AN | `20039987` |

Quét QR bằng app ngân hàng bất kỳ (VietQR / Napas 247). Cảm ơn bạn rất nhiều! ❤️
