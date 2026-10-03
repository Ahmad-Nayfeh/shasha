@echo off
rem Shasha installer for Windows: double-click this file.
rem It installs the app on the TV over the network and grants the permissions that only ADB can
rem grant (SPEC section 4), with the adb bundled in tools\windows (Google's Platform-Tools).
rem   double-click, or: "this file" (asks for the TV's address)
rem   "this file" TV_IP[:port] [path\to\shasha.apk]
rem   "this file" --undo [TV_IP[:port]]
rem Drop an APK on it to install that file. Step by step: the guide PDF next to this file (source:
rem docs\guide). Details: README.md in the Shasha-Setup folder of the repository.
rem Keep this file UTF-8 WITHOUT BOM, with CRLF line endings. Lines before "chcp 65001" stay ASCII.
setlocal EnableExtensions DisableDelayedExpansion

rem Remember the console code page, then switch to UTF-8 so the Arabic lines print.
set "OLDCP="
for /f "tokens=2 delims=:." %%c in ('chcp') do set /a "OLDCP=%%c" >nul 2>&1
chcp 65001 >nul
title Shasha - شاشة

set "PKG=com.ahmadnayfeh.shasha"
set "ACTIVITY=com.ahmadnayfeh.shasha/.MainActivity"
rem The app starts its guard itself (at its start and after a reboot); this script only reports whether it runs.
set "GUARD=com.ahmadnayfeh.shasha/.lock.GuardService"
rem The official YouTube app: Google's on Android TV and Google TV, YouTube for Fire TV on Fire TV. YT_PKG is
rem the one this TV has, found after connecting.
set "YT_PKGS=com.google.android.youtube.tv com.amazon.firetv.youtube"
set "YT_PKG="
rem SYSTEM_ALERT_WINDOW: the guard draws over other apps. GET_USAGE_STATS: it sees which app is
rem in front. REQUEST_INSTALL_PACKAGES: the in-app updater may open the system installer.
set "OPS=SYSTEM_ALERT_WINDOW GET_USAGE_STATS REQUEST_INSTALL_PACKAGES"
rem The guard's two permissions behind those app-ops. They are development permissions: an install
rem with -g, as older versions of this script did, grants them for good, and an app-op reset to
rem default then falls back to them, so --undo revokes them too.
set "GUARD_PERMS=android.permission.SYSTEM_ALERT_WINDOW android.permission.PACKAGE_USAGE_STATS"
set "CONNECT_TIMEOUT=60"
rem Wrong answers in a row, an address or a pairing code, before the script gives up.
set "MAX_TRIES=5"
rem Shasha's minSdk: Android 7.1, which is Fire OS 6.
set "MIN_SDK=25"
rem Set after connecting: the API level, and LEGACY=1 on Android 7.1 and older (Fire OS 6), which have no
rem REQUEST_INSTALL_PACKAGES app-op: the TV-wide Unknown sources setting is used instead.
set "TV_SDK="
set "LEGACY=0"
set "UNDO_FAILED=0"
rem Taken before any shift: shift moves the script's own name too.
set "SELF=%~f0"
set "SELF_NAME=%~nx0"
set "SCRIPT_DIR=%~dp0"
set "TMPOUT=%TEMP%\shasha-setup-%RANDOM%.txt"
rem The TV's maker, model and Android version, as the TV wrote them (printed only through for /f).
set "TVINFO=%TEMP%\shasha-setup-tv-%RANDOM%.txt"
set "UNDO=0"
set "TV="
set "APK="
rem Started without an address, by a double-click: wait for a key before the window closes.
set "INTERACTIVE=0"
set "EXITCODE=0"
set "OPS_OK=1"
set "OPS_FAILED=0"
set "STOP_ADB_SERVER=0"
set "ADB="
set "YOUTUBE_STATUS=not installed / غير مثبّت"
set "GUARD_STATUS=not running now / لا تعمل الآن"
set "UNDO_GUARD=-"

rem ---------------------------------------------------------------- arguments
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--undo" (set "UNDO=1" & shift & goto parse)
if /i "%~1"=="--help" goto usage
if /i "%~1"=="-h" goto usage
set "ARG=%~1"
if "%ARG:~0,1%"=="-" goto bad_usage
if /i "%~x1"==".apk" (
  if defined APK goto bad_usage
  set "APK=%~f1"
) else (
  if defined TV goto bad_usage
  set "TV=%~1"
)
shift
goto parse

:parsed
if not defined TV set "INTERACTIVE=1"
if not defined TV goto banner
rem Quotes go in the copy itself: a stray quote in an argument would otherwise end the string.
set "INPUT=%TV:"=%"
call :clean_input
if not defined INPUT goto bad_tv
set "ADDR=%INPUT%"
call :check_addr any
if "%ADDR_OK%"=="0" goto bad_tv
set "TV=%ADDR%"

:banner
echo(
if "%UNDO%"=="1" echo(  Shasha: undo / شاشة: التراجع
if not "%UNDO%"=="1" echo(  Shasha installer / مثبّت «شاشة»

rem ---------------------------------------------------------------- adb
call :say "Checking adb ..." "التحقق من وجود adb ..."
call :find_adb
if defined ADB goto adb_found
if defined BROKEN_ADB goto adb_broken
goto no_adb
:adb_found
echo(    "%ADB%"
for /f "delims=" %%v in ("%ADB_VERSION%") do echo(    %%v

rem ---------------------------------------------------------------- the TV's address
if defined TV goto have_tv
echo(
echo(==^> Type the TV's IP address
echo(    اكتب عنوان IP للشاشة
echo(    - Google TV / Android TV: Settings ^> Network ^& Internet ^> your Wi-Fi ^> IP address
echo(      (with a cable: Ethernet ^> under «Connected»).
echo(      الإعدادات ← الشبكة والإنترنت ← شبكتك ← IP address (بالسلك: Ethernet ← تحت «Connected»).
echo(    - Fire TV: Settings ^> My Fire TV (on Fire TV sets: Device ^& Software) ^> About ^> Network.
echo(      الإعدادات ← My Fire TV (في أجهزة التلفاز: Device ^& Software) ← About ← Network.
echo(    Example / مثال: 192.168.1.50
call :read_addr any "IP: "
if errorlevel 1 goto no_address
set "TV=%ADDR%"
:have_tv
rem Only digits, dots and ":port" passed check_addr, so TV and SERIAL are safe unquoted from here on.
set "SERIAL=%TV%"
if "%TV::=%"=="%TV%" set "SERIAL=%TV%:5555"

if "%UNDO%"=="1" goto undo

rem ---------------------------------------------------------------- setup
if defined APK goto apk_chosen
for /f "delims=" %%f in ('dir /b /a-d /o-d "%SCRIPT_DIR%shasha-*.apk" 2^>nul') do if not defined APK set "APK=%SCRIPT_DIR%%%f"
if defined APK goto apk_chosen
for /f "delims=" %%f in ('dir /b /a-d /o-d "%CD%\shasha-*.apk" 2^>nul') do if not defined APK set "APK=%CD%\%%f"
if defined APK goto apk_chosen
call :warn "No APK given and no shasha-*.apk next to the installer or in the current folder." "لا يوجد ملف التطبيق shasha-*.apk بجانب المثبّت. فك ضغط Shasha-Setup.zip كاملاً وشغّل المثبّت من المجلد الناتج."
goto fail

:apk_chosen
rem The path is user input: it is only ever used inside quotes, never passed through call.
if exist "%APK%" goto apk_found
call :warn "APK not found:" "ملف APK غير موجود:"
echo(    "%APK%"
goto fail
:apk_found
echo(    APK: "%APK%"

call :connect
if errorlevel 1 goto fail
call :describe_tv
if errorlevel 1 goto fail

call :say "Installing the APK ..." "تثبيت التطبيق على الشاشة، قد يستغرق دقيقة ..."
rem No -g: it would grant the guard's permissions for good, beyond the app-ops below and beyond --undo.
"%ADB%" -s %SERIAL% install -r "%APK%" <nul >"%TMPOUT%" 2>&1
if errorlevel 1 goto install_failed
rem Old adb versions exit 0 even when the install fails: require the "Success" line too.
findstr /l /b /c:"Success" "%TMPOUT%" >nul || goto install_failed
echo(    Success
rem Uninstalling from the TV's Settings can leave the app removed for the current user only: after an update it
rem then stays invisible, with the error Activity class does not exist. install-existing brings it back; on a
rem normal install it changes nothing. Its output and errors are ignored.
"%ADB%" -s %SERIAL% shell cmd package install-existing %PKG% <nul >nul 2>&1
goto grant

:install_failed
type "%TMPOUT%"
findstr /l /i /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" /c:"signatures do not match" "%TMPOUT%" >nul && goto install_other_key
findstr /l /i /c:"INSTALL_FAILED_VERSION_DOWNGRADE" "%TMPOUT%" >nul && goto install_downgrade
findstr /l /i /c:"INSTALL_FAILED_OLDER_SDK" "%TMPOUT%" >nul && goto install_too_old
findstr /l /i /c:"NO_CERTIFICATES" /c:"no signatures" "%TMPOUT%" >nul && goto install_unsigned
call :warn "Installation failed, see the message above." "فشل التثبيت، انظر الرسالة أعلاه."
goto fail

:install_other_key
call :warn "The Shasha already on the TV was signed with a different key, an old or test build." "النسخة المثبّتة على الشاشة موقّعة بمفتاح آخر، بناء قديم أو تجريبي، فلا يمكن التحديث فوقها."
call :info "Nothing was changed. To replace it, uninstall it first. This DELETES its data:"
call :info "profiles, subscriptions, history. Export a backup in Shasha's settings first."
call :info "لم يتغيّر شيء. لاستبدالها يجب حذفها أولاً، وهذا يمسح بياناتها: الملفات والاشتراكات والسجل."
call :info "صدّر نسخة احتياطية من إعدادات التطبيق قبل الحذف إن احتجت إليها."
call :info "To uninstall on the TV: Settings, Apps, شاشة, Uninstall. Or from this computer:"
call :info "للحذف من الشاشة: الإعدادات ← التطبيقات ← شاشة ← Uninstall. أو من هذا الحاسوب:"
echo(
echo(        "%ADB%" -s %SERIAL% uninstall %PKG%
echo(
call :info "Then run the installer again. / ثم شغّل المثبّت من جديد."
goto fail

:install_downgrade
call :warn "The TV already has a newer Shasha than this APK." "النسخة على الشاشة أحدث من هذا الملف. حمّل أحدث ملف من صفحة GitHub Releases."
goto fail

:install_too_old
call :too_old_help
goto fail

:install_unsigned
call :warn "This APK is not signed, a CI build made without the signing key." "هذا الملف غير موقّع، بناء بلا مفتاح التوقيع. حمّل الملف من صفحة GitHub Releases."
goto fail

:grant
call :say "Granting permissions: display over apps, usage access, install updates ..." "منح الصلاحيات: العرض فوق التطبيقات، الوصول إلى الاستخدام، تثبيت التحديثات ..."
for %%o in (%OPS%) do call :appops_set %%o allow
if "%OPS_FAILED%"=="1" goto fail
if "%LEGACY%"=="1" call :unknown_sources_set 1
if "%OPS_FAILED%"=="1" goto fail

rem ---------------------------------------------------------------- official YouTube app
set "YT_PKG="
for %%y in (%YT_PKGS%) do if not defined YT_PKG call :find_youtube %%y
if not defined YT_PKG goto youtube_absent
call :find_package disabled %YT_PKG%
if "%FOUND%"=="1" goto youtube_already_disabled
echo(
echo(==^> Disable the official YouTube app on the TV? (it can be re-enabled any time)
echo(    هل تريد تعطيل تطبيق يوتيوب الرسمي على الشاشة؟ (يمكن إعادته في أي وقت)
rem set /p, not choice: a plain Enter must mean No.
call :ask_yes_no "Disable YouTube? [y/N] "
if "%YES%"=="1" goto youtube_disable
goto youtube_keep

:youtube_disable
"%ADB%" -s %SERIAL% shell pm disable-user --user 0 %YT_PKG% <nul
if errorlevel 1 (
  call :warn "Could not disable YouTube, see above." "تعذّر تعطيل يوتيوب، انظر الرسالة أعلاه."
  goto fail
)
set "YOUTUBE_STATUS=disabled / معطّل"
call :info "Disabled. To bring it back: / للإعادة:"
echo(        "%ADB%" -s %SERIAL% shell pm enable %YT_PKG%
goto start_app

:youtube_keep
set "YOUTUBE_STATUS=enabled / مفعّل"
call :info "Left enabled. / تُرك مفعّلاً."
goto start_app

:youtube_already_disabled
set "YOUTUBE_STATUS=disabled / معطّل"
call :info "The official YouTube app is already disabled. / تطبيق يوتيوب الرسمي معطّل أصلاً."
goto start_app

:youtube_absent
call :info "Official YouTube app not found on the TV; skipping. / تطبيق يوتيوب الرسمي غير موجود؛ تخطٍّ."
goto start_app

rem ---------------------------------------------------------------- start, verify, summary
:start_app
call :say "Starting Shasha ..." "تشغيل التطبيق ..."
"%ADB%" -s %SERIAL% shell am start -n %ACTIVITY% <nul >"%TMPOUT%" 2>&1
findstr /l /b /c:"Error" "%TMPOUT%" >nul && goto start_failed
rem About 3 seconds for the app to start its guard before it is checked.
ping -n 4 127.0.0.1 >nul
goto verification
:start_failed
type "%TMPOUT%"
call :warn "Could not start the app; open it from the TV home screen." "تعذّر تشغيل التطبيق؛ افتحه يدوياً من الشاشة الرئيسية."

:verification
call :say "Verification" "التحقق"
set "OPS_OK=1"
for %%o in (%OPS%) do call :check_op %%o allow
if "%LEGACY%"=="1" call :check_unknown_sources 1
"%ADB%" -s %SERIAL% shell dumpsys activity services %GUARD% <nul >"%TMPOUT%" 2>&1
findstr /l /c:"ServiceRecord{" "%TMPOUT%" >nul && set "GUARD_STATUS=running / تعمل"
echo(    Guard service / خدمة الحارس: %GUARD_STATUS%
set "OPS_TEXT=granted / ممنوحة"
if "%OPS_OK%"=="0" set "OPS_TEXT=INCOMPLETE / ناقصة"

call :say "Summary" "الملخص"
echo(    TV / الشاشة:              %SERIAL%
for /f "usebackq delims=" %%l in ("%TVINFO%") do echo(    Model / الطراز:            %%l
for %%a in ("%APK%") do echo(    APK / الملف:               %%~nxa
echo(    Permissions / الصلاحيات:   %OPS_TEXT%
echo(    Guard / الحارس:            %GUARD_STATUS%
echo(    YouTube / يوتيوب الرسمي:  %YOUTUBE_STATUS%
echo(    Undo / للتراجع:            "%SELF%" --undo %SERIAL%
echo(
call :info "The app runs its guard from its first start and after every reboot; it acts only while"
call :info "a child profile is on screen, a video is locked, time is up or it is outside the schedule. To double-check, open"
call :info "Shasha, Settings, Protection status on the TV (it also shows how fast the app came back)."
call :info "التطبيق يشغّل الحارس من أول تشغيل وبعد كل إعادة تشغيل للشاشة، ويتدخل طوال بقاء ملف طفل على الشاشة، وأثناء قفل فيديو، وبعد انتهاء الوقت، وخارج الجدول."
call :info "للتأكد: افتح تطبيق شاشة، ثم الإعدادات، ثم حالة الحماية (وفيها سرعة العودة المقيسة)."
if "%OPS_OK%"=="0" goto fail
call :say "Done! Shasha is installed on the TV." "تمّ! «شاشة» مثبّت على الشاشة."
call :info "Now continue on the TV: create the parent PIN and the profiles."
call :info "تابع الآن على الشاشة: أنشئ رمز الوالدين والملفات."
goto finish

rem ---------------------------------------------------------------- undo
:undo
if defined APK call :warn "--undo ignores the APK argument." "خيار --undo يتجاهل ملف APK."
call :connect
if errorlevel 1 goto fail
call :describe_tv
if errorlevel 1 goto fail
for %%y in (%YT_PKGS%) do call :undo_youtube %%y
if "%UNDO_FAILED%"=="1" goto fail

:undo_ops
set "OPS_TEXT=app not installed / التطبيق غير مثبّت"
call :find_package all %PKG%
if "%FOUND%"=="0" goto undo_summary
call :say "Resetting Shasha's permissions to default ..." "إرجاع صلاحيات شاشة إلى الوضع الافتراضي ..."
rem Revoked first: a guard that sees its app-ops change then finds nothing left and stops by itself.
rem Not granted is not an error here, and a real failure shows in the verification below.
for %%p in (%GUARD_PERMS%) do "%ADB%" -s %SERIAL% shell pm revoke %PKG% %%p <nul >nul 2>&1
for %%o in (%OPS%) do call :appops_set %%o default
if "%OPS_FAILED%"=="1" goto fail
if "%LEGACY%"=="1" call :unknown_sources_set 0
if "%OPS_FAILED%"=="1" goto fail
if "%LEGACY%"=="1" call :info "Other apps you install by hand may need Unknown sources on again. Fire TV: Developer options, Apps from Unknown Sources."
if "%LEGACY%"=="1" call :info "قد تحتاج تشغيل «مصادر غير معروفة» لتطبيقات أخرى تثبّتها بنفسك. في Fire TV: Developer options ← Apps from Unknown Sources."
call :say "Stopping Shasha so its guard stops now ..." "إيقاف تطبيق شاشة ليتوقف الحارس الآن ..."
call :info "It starts again only once its permissions are granted again: run the installer without --undo."
call :info "لا يعود الحارس حتى تُمنح صلاحيتاه من جديد: شغّل المثبّت دون --undo."
"%ADB%" -s %SERIAL% shell am force-stop %PKG% <nul
call :say "Verification" "التحقق"
set "OPS_OK=1"
for %%o in (%OPS%) do call :check_op %%o default
if "%LEGACY%"=="1" call :check_unknown_sources 0
call :check_perms
set "OPS_TEXT=default / افتراضية"
if "%OPS_OK%"=="0" set "OPS_TEXT=CHECK ABOVE / راجع أعلاه"
"%ADB%" -s %SERIAL% shell dumpsys activity services %GUARD% <nul >"%TMPOUT%" 2>&1
set "UNDO_GUARD=%GUARD_STATUS%"
findstr /l /c:"ServiceRecord{" "%TMPOUT%" >nul && set "UNDO_GUARD=running / تعمل"
echo(    Guard service / خدمة الحارس: %UNDO_GUARD%

:undo_summary
call :say "Summary" "الملخص"
echo(    TV / الشاشة:              %SERIAL%
for /f "usebackq delims=" %%l in ("%TVINFO%") do echo(    Model / الطراز:            %%l
echo(    Permissions / الصلاحيات:   %OPS_TEXT%
echo(    Guard / الحارس:            %UNDO_GUARD%
echo(    YouTube / يوتيوب الرسمي:  %YOUTUBE_STATUS%
call :info "Shasha stays installed. To remove it: TV Settings, Apps, شاشة, Uninstall, or:"
call :info "التطبيق باقٍ. لحذفه: الإعدادات ← التطبيقات ← شاشة ← Uninstall، أو:"
echo(        "%ADB%" -s %SERIAL% uninstall %PKG%
if "%OPS_OK%"=="0" goto fail
goto finish

rem ---------------------------------------------------------------- endings
:no_adb
call :warn "The installer's adb was not found." "لم يُعثر على أداة adb الخاصة بالمثبّت."
echo(    Expected / المتوقع: "%SCRIPT_DIR%tools\windows\adb.exe"
call :info "- Extract the WHOLE Shasha-Setup.zip first: right-click it, then Extract All."
call :info "  Then open the extracted Shasha-Setup folder and double-click the installer again."
call :info "  فك ضغط Shasha-Setup.zip كاملاً أولاً: انقر عليه بالزر الأيمن ثم Extract All،"
call :info "  ثم افتح مجلد Shasha-Setup الناتج وانقر المثبّت مرتين من جديد."
call :info "- Keep the installer next to its tools folder and the .apk file; do not move it alone."
call :info "  أبقِ المثبّت بجانب مجلد tools وملف التطبيق .apk، ولا تنقله وحده."
set "INTERACTIVE=1"
goto fail

:adb_broken
call :warn "adb could not start:" "تعذّر تشغيل أداة adb:"
echo(    "%BROKEN_ADB%"
call :info "- Windows or an antivirus program may have blocked it. Extract Shasha-Setup.zip again"
call :info "  into a folder of your own, like Downloads, and run the installer from there."
call :info "  ربما منعها ويندوز أو برنامج مكافحة الفيروسات. فك ضغط Shasha-Setup.zip من جديد"
call :info "  في مجلد لك مثل التنزيلات Downloads، وشغّل المثبّت من هناك."
set "INTERACTIVE=1"
goto fail

:no_address
call :warn "No valid address was typed." "لم يُكتب عنوان صحيح. شغّل المثبّت من جديد."
set "EXITCODE=2"
goto finish

:bad_tv
call :warn "Not an IP address like 192.168.1.50 or 192.168.1.50:5555:" "ليس عنوان IP مثل 192.168.1.50. تجده في إعدادات الشاشة، الشبكة والإنترنت:"
echo(    "%TV:"=%"
goto fail

:usage
call :usage_text
goto finish

:bad_usage
call :usage_text
set "EXITCODE=2"
goto finish

:fail
set "EXITCODE=1"

:finish
rem The bundled adb's background server would otherwise keep running from this folder.
if "%STOP_ADB_SERVER%"=="1" "%ADB%" kill-server <nul >nul 2>&1
if exist "%TMPOUT%" del /q "%TMPOUT%" >nul 2>&1
if exist "%TVINFO%" del /q "%TVINFO%" >nul 2>&1
if "%INTERACTIVE%"=="1" (
  echo(
  echo(    Press any key to close. / اضغط أي زر للإغلاق.
  pause >nul
)
if defined OLDCP if not "%OLDCP%"=="0" chcp %OLDCP% >nul 2>&1
exit /b %EXITCODE%

rem ================================================================ subroutines
rem Messages passed to say, warn and info are fixed text: no double quotes, ampersands, pipes,
rem angle brackets, carets or percent signs, because call parses them again as syntax.

:say
echo(
echo(==^> %~1
echo(    %~2
exit /b 0

:warn
echo(
echo(!!  %~1
echo(    %~2
exit /b 0

:info
echo(    %~1
exit /b 0

:usage_text
echo Usage:
echo   "%SELF_NAME%"                          asks for the TV's address, then installs
echo   "%SELF_NAME%" TV_IP [shasha.apk]       install Shasha and grant its permissions
echo   "%SELF_NAME%" --undo [TV_IP]           re-enable YouTube, reset the permissions, stop the guard
echo TV_IP may carry a port, default 5555: 192.168.1.50 or 192.168.1.50:5555
echo Without an APK path: the newest shasha-*.apk next to this file, else in the current folder.
echo(
echo الاستخدام:
echo   "%SELF_NAME%"                          يسأل عن عنوان الشاشة ثم يثبّت
echo   "%SELF_NAME%" TV_IP [ملف APK]          تثبيت شاشة ومنح الصلاحيات
echo   "%SELF_NAME%" --undo [TV_IP]           التراجع: إعادة يوتيوب وإلغاء الصلاحيات وإيقاف الحارس
exit /b 0

rem ---------------------------------------------------------------- adb
rem The adb bundled with this kit first, then one on PATH, then the usual places. Sets ADB to the
rem first that runs, and BROKEN_ADB to the first one found that does not run.
:find_adb
set "ADB="
set "BROKEN_ADB="
set "CAND=%SCRIPT_DIR%tools\windows\adb.exe"
call :try_adb
if defined ADB exit /b 0
for /f "delims=" %%p in ('where adb.exe 2^>nul') do if not defined ADB (set "CAND=%%p" & call :try_adb)
if defined ADB exit /b 0
set "CAND=%SCRIPT_DIR%platform-tools\adb.exe"
call :try_adb
if defined ADB exit /b 0
set "CAND=%CD%\platform-tools\adb.exe"
call :try_adb
if defined ADB exit /b 0
set "CAND=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
call :try_adb
exit /b 0

rem CAND = a possible adb.exe. The path goes through a variable, not through call, which would
rem parse it again. Sets ADB and ADB_VERSION when it runs.
:try_adb
if not exist "%CAND%" exit /b 0
"%CAND%" version <nul >"%TMPOUT%" 2>&1
if errorlevel 1 goto try_adb_broken
set "ADB=%CAND%"
set "ADB_VERSION="
for /f "usebackq delims=" %%v in ("%TMPOUT%") do if not defined ADB_VERSION set "ADB_VERSION=%%v"
exit /b 0
:try_adb_broken
if not defined BROKEN_ADB set "BROKEN_ADB=%CAND%"
exit /b 0

rem ---------------------------------------------------------------- what the user types
rem Cleans INPUT: drops quotes and spaces; turns Arabic-Indic digits and the Arabic decimal point
rem into 0-9 and a dot, as an Arabic keyboard may type them.
:clean_input
if not defined INPUT exit /b 0
set "INPUT=%INPUT:"=%"
if not defined INPUT exit /b 0
set "INPUT=%INPUT: =%"
if not defined INPUT exit /b 0
set "INPUT=%INPUT:٠=0%"
set "INPUT=%INPUT:١=1%"
set "INPUT=%INPUT:٢=2%"
set "INPUT=%INPUT:٣=3%"
set "INPUT=%INPUT:٤=4%"
set "INPUT=%INPUT:٥=5%"
set "INPUT=%INPUT:٦=6%"
set "INPUT=%INPUT:٧=7%"
set "INPUT=%INPUT:٨=8%"
set "INPUT=%INPUT:٩=9%"
set "INPUT=%INPUT:۰=0%"
set "INPUT=%INPUT:۱=1%"
set "INPUT=%INPUT:۲=2%"
set "INPUT=%INPUT:۳=3%"
set "INPUT=%INPUT:۴=4%"
set "INPUT=%INPUT:۵=5%"
set "INPUT=%INPUT:۶=6%"
set "INPUT=%INPUT:۷=7%"
set "INPUT=%INPUT:۸=8%"
set "INPUT=%INPUT:۹=9%"
set "INPUT=%INPUT:٫=.%"
exit /b 0

rem Sets ADDR_OK=1 when ADDR is a.b.c.d, each part 0-255, with an optional :port 1-65535.
rem %1 = port when the port is required, else any. Only digits, dots and colons pass the first
rem test, so ADDR is safe unquoted after it: anything else, like & or >, would be cmd syntax.
:check_addr
set "ADDR_OK=0"
for /f "tokens=1* delims=0123456789.:" %%a in ("x%ADDR%") do if not "%%a%%b"=="x" exit /b 0
>"%TMPOUT%" echo(%ADDR%
if "%~1"=="port" goto check_addr_port
rem /i: findstr can miss matches with several case-sensitive search strings of different lengths.
findstr /r /i /x /c:"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*" /c:"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*:[0-9][0-9]*" "%TMPOUT%" >nul || exit /b 0
goto check_addr_range
:check_addr_port
findstr /r /i /x /c:"[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*:[0-9][0-9]*" "%TMPOUT%" >nul || exit /b 0
:check_addr_range
for /f "tokens=1-5 delims=.:" %%a in ("%ADDR%") do call :check_parts %%a %%b %%c %%d %%e
exit /b 0
:check_parts
for %%n in (%1 %2 %3 %4) do if %%n GTR 255 exit /b 0
rem No port: skip. An empty %5 in an if comparison is a syntax error that ends the script, even
rem behind a false test on the same line, because cmd parses the whole line first.
if "%~5"=="" goto check_parts_ok
if %~5 GTR 65535 exit /b 0
if %~5 LSS 1 exit /b 0
:check_parts_ok
set "ADDR_OK=1"
exit /b 0

rem Reads an address into ADDR. %1 = port when a port is required, else any; %2 = the label
rem before the answer. Returns 1 at the end of the input or after MAX_TRIES wrong answers.
:read_addr
set "ADDR_NEED=%~1"
set "ADDR_LABEL=%~2"
set "TRIES=0"
:read_addr_again
set /a "TRIES+=1"
if %TRIES% GTR %MAX_TRIES% exit /b 1
set "INPUT="
set /p "INPUT=%ADDR_LABEL%"
call :clean_input
if not defined INPUT goto read_addr_again
set "ADDR=%INPUT%"
call :check_addr %ADDR_NEED%
if "%ADDR_OK%"=="1" exit /b 0
if "%ADDR_NEED%"=="port" goto read_addr_bad_port
call :warn "Not an IP address like 192.168.1.50:" "ليس عنوان IP مثل 192.168.1.50. اكتبه كما يظهر في الشاشة:"
echo(    "%ADDR%"
goto read_addr_again
:read_addr_bad_port
call :warn "Not an address with a port, like 192.168.1.50:37215:" "ليس عنواناً بمنفذ مثل 192.168.1.50:37215. اكتبه كما يظهر في الشاشة:"
echo(    "%ADDR%"
goto read_addr_again

rem Reads the 6-digit Wireless debugging pairing code into CODE. Returns 1 as read_addr does.
:read_code
set "TRIES=0"
:read_code_again
set /a "TRIES+=1"
if %TRIES% GTR %MAX_TRIES% exit /b 1
set "INPUT="
set /p "INPUT=Code / الرمز: "
call :clean_input
if not defined INPUT goto read_code_again
set "CODE=%INPUT%"
for /f "tokens=1* delims=0123456789" %%a in ("x%CODE%") do if not "%%a%%b"=="x" goto read_code_bad
if "%CODE:~5,1%"=="" goto read_code_bad
if not "%CODE:~6,1%"=="" goto read_code_bad
exit /b 0
:read_code_bad
call :warn "The code has 6 digits, like 123456." "الرمز 6 أرقام، مثل 123456."
goto read_code_again

rem A y/N question; %1 = the label before the answer. Sets YES=1 only for y, yes or نعم.
:ask_yes_no
set "YES=0"
call :info "Type y (yes) or n (no), then press Enter. / اكتب y (نعم) أو n (لا) ثم اضغط Enter."
set "INPUT="
set /p "INPUT=%~1"
call :clean_input
if not defined INPUT exit /b 0
if /i "%INPUT%"=="y" set "YES=1"
if /i "%INPUT%"=="yes" set "YES=1"
if "%INPUT%"=="نعم" set "YES=1"
exit /b 0

rem ---------------------------------------------------------------- connecting
rem Connects to SERIAL; when the TV refuses, offers Wireless debugging pairing. Returns 1 on failure.
:connect
call :try_connect
if "%CONNECT_RESULT%"=="ok" exit /b 0
if "%CONNECT_RESULT%"=="refused" goto connect_refused
if "%CONNECT_RESULT%"=="unauthorized" goto connect_unauthorized
:connect_unreachable
call :unreachable_help
exit /b 1
:connect_unauthorized
call :unauthorized_help
exit /b 1
:connect_refused
call :refused_help
echo(
echo(==^> Does your TV only have «Wireless debugging»? Pair with a code now?
echo(    هل في شاشتك «Wireless debugging» فقط؟ هل تريد الإقران برمز الآن؟
call :ask_yes_no "Pair now? [y/N] "
if "%YES%"=="1" goto pair
call :info "Turn on «USB debugging», then run the installer again. / فعّل «USB debugging» ثم شغّل المثبّت من جديد."
exit /b 1

rem One attempt of up to CONNECT_TIMEOUT seconds. Sets CONNECT_RESULT to ok, refused: nothing
rem listens on that port, unreachable: no answer, or unauthorized: the TV did not allow this computer.
:try_connect
call :say "Connecting to %SERIAL% ..." "الاتصال بالشاشة %SERIAL% ..."
set "CONNECT_RESULT=unreachable"
rem The bundled adb's background server is stopped at the end: it would keep running from this folder.
if /i "%ADB%"=="%SCRIPT_DIR%tools\windows\adb.exe" set "STOP_ADB_SERVER=1"
"%ADB%" start-server <nul >nul 2>&1
call :device_state
rem A connection an earlier run left unauthorized or offline does not ask the TV again: start anew.
if defined STATE if not "%STATE%"=="device" "%ADB%" disconnect %SERIAL% <nul >nul 2>&1
set "REFUSED=0"
call :adb_connect show
set "WAITED=0"
set "LAST_TRY=0"
set "HINTED=0"
:try_connect_loop
call :device_state
if "%STATE%"=="device" goto try_connect_ok
if "%STATE%"=="unauthorized" goto try_connect_unauthorized
if "%REFUSED%"=="1" goto try_connect_refused
rem Not listed yet, or offline: ask again every 6 s.
set /a "SINCE=WAITED-LAST_TRY"
if %SINCE% LSS 6 goto try_connect_wait
if "%STATE%"=="offline" "%ADB%" disconnect %SERIAL% <nul >nul 2>&1
call :adb_connect quiet
set "LAST_TRY=%WAITED%"
goto try_connect_wait
:try_connect_unauthorized
if "%HINTED%"=="1" goto try_connect_wait
set "HINTED=1"
call :allow_hint
:try_connect_wait
if %WAITED% GEQ %CONNECT_TIMEOUT% goto try_connect_timeout
rem About 2 seconds; timeout.exe fails when input is redirected, ping does not.
ping -n 3 127.0.0.1 >nul
set /a "WAITED+=2"
goto try_connect_loop
:try_connect_timeout
if "%STATE%"=="unauthorized" set "CONNECT_RESULT=unauthorized"
exit /b 0
:try_connect_refused
set "CONNECT_RESULT=refused"
exit /b 0
:try_connect_ok
call :info "Connected. / تم الاتصال."
set "CONNECT_RESULT=ok"
exit /b 0

rem %1 = show or quiet. Sets REFUSED=1 when the TV refused: Connection refused, or on Windows
rem error 10061, whose text may be translated.
:adb_connect
"%ADB%" connect %SERIAL% <nul >"%TMPOUT%" 2>&1
if "%~1"=="show" for /f "usebackq delims=" %%l in ("%TMPOUT%") do echo(    %%l
findstr /i /c:"refused" /c:"10061" "%TMPOUT%" >nul && set "REFUSED=1"
exit /b 0

:device_state
set "STATE="
"%ADB%" devices <nul >"%TMPOUT%" 2>nul
for /f "usebackq tokens=1,2" %%a in ("%TMPOUT%") do if /i "%%a"=="%SERIAL%" set "STATE=%%b"
exit /b 0

:allow_hint
echo(
echo(==^> Look at the TV: it asks «Allow USB debugging?» (Fire TV asks the same)
echo(    انظر إلى الشاشة: تظهر نافذة «Allow USB debugging?» (السماح بتصحيح الأخطاء)، وكذلك في Fire TV.
echo(    With the remote, tick «Always allow from this computer», then press OK.
echo(    بالريموت: فعّل «Always allow from this computer» (السماح دائماً من هذا الحاسوب) ثم اضغط OK.
echo(    Waiting up to %CONNECT_TIMEOUT% s ... / بانتظار الموافقة حتى %CONNECT_TIMEOUT% ثانية ...
exit /b 0

:unauthorized_help
echo(
echo(!!  The TV did not allow this computer (state: unauthorized).
echo(    لم تسمح الشاشة لهذا الحاسوب بعد.
echo(    - Run the installer again and watch the TV: when «Allow USB debugging?» appears,
echo(      tick «Always allow from this computer» and press OK (within %CONNECT_TIMEOUT% seconds).
echo(      شغّل المثبّت من جديد وراقب الشاشة: عند ظهور «Allow USB debugging?» فعّل
echo(      «Always allow from this computer» واضغط OK (خلال %CONNECT_TIMEOUT% ثانية).
echo(    - Still stuck: turn «USB debugging» off and on again on the TV, then run the installer again.
echo(      إن استمرت المشكلة: أطفئ «USB debugging» في الشاشة ثم شغّله، وأعد تشغيل المثبّت.
exit /b 0

:unreachable_help
if not defined STATE set "STATE=not found"
echo(
echo(!!  Could not reach the TV at %SERIAL% (state: %STATE%).
echo(    تعذّر الوصول إلى الشاشة على %SERIAL%.
echo(    - Is the TV on, and on the SAME home network as this computer (Wi-Fi or cable)?
echo(      هل الشاشة تعمل، وعلى شبكة البيت نفسها التي عليها الحاسوب (Wi-Fi أو سلك)؟
echo(    - Is the address right? TV Settings ^> Network ^& Internet ^> your network ^> IP address.
echo(      هل العنوان صحيح؟ الإعدادات ← الشبكة والإنترنت ← شبكتك ← IP address.
echo(      With a cable: Network ^& Internet ^> Ethernet ^> the number under «Connected».
echo(      بالسلك: الشبكة والإنترنت ← Ethernet ← الرقم تحت «Connected».
echo(      Fire TV: Settings ^> My Fire TV (or Device ^& Software) ^> About ^> Network.
echo(      في Fire TV: الإعدادات ← My Fire TV (أو Device ^& Software) ← About ← Network.
echo(    - Developer options ^> «USB debugging» (and «Network debugging», if present) must be ON.
echo(      On Fire TV: Developer options ^> «ADB debugging».
echo(      يجب تفعيل خيارات المطوّر ← «USB debugging» (و«Network debugging» إن وُجد)، وفي Fire TV: «ADB debugging».
echo(    - Still stuck: run the command below, then try again. / إن استمرت المشكلة نفّذ الأمر التالي ثم أعد المحاولة:
echo(        "%ADB%" kill-server
exit /b 0

:refused_help
echo(
echo(!!  The TV refused the connection on %SERIAL%.
echo(    رفضت الشاشة الاتصال على %SERIAL%.
echo(    - Most often debugging is off. Turn it on, then run the installer again:
echo(      غالباً تصحيح الأخطاء غير مفعّل. فعّله ثم شغّل المثبّت من جديد:
echo(      Google TV / Android TV: Settings ^> System ^> Developer options ^> «USB debugging»
echo(      (and «Network debugging» if it exists).
echo(      الإعدادات ← النظام ← خيارات المطوّر ← «USB debugging» (و«Network debugging» إن وُجد).
echo(      Fire TV: Settings ^> My Fire TV (or Device ^& Software) ^> Developer options ^> «ADB debugging».
echo(      في Fire TV: الإعدادات ← My Fire TV (أو Device ^& Software) ← Developer options ← «ADB debugging».
echo(    - No «Developer options»? Open «About» and press OK 7 times on «Android TV OS build» (or «Build»);
echo(      on Fire TV, on the device's name (like «Fire TV Stick»). Then go back: it is there.
echo(      لا ترى خيارات المطوّر؟ افتح «About» واضغط OK سبع مرات على «Android TV OS build» (أو «Build»)،
echo(      وفي Fire TV على اسم الجهاز (مثل «Fire TV Stick»)، ثم ارجع: ستظهر.
echo(    - Some TVs only have «Wireless debugging»: the installer can pair with it using a 6-digit code.
echo(      بعض الشاشات ليس فيها إلا «Wireless debugging»: يستطيع المثبّت الإقران بها برمز من 6 أرقام.
exit /b 0

rem Wireless debugging: pair with the 6-digit code, then connect to the address it listens on.
rem Reached by goto from connect, so its exit /b returns from connect.
:pair
echo(
echo(==^> Pairing with a code (Wireless debugging)
echo(    الإقران برمز (Wireless debugging)
echo(    On the TV: Settings ^> System ^> Developer options ^> «Wireless debugging»: turn it on,
echo(    then choose «Pair device with pairing code». The TV shows a 6-digit code and an address.
echo(    في الشاشة: الإعدادات ← النظام ← خيارات المطوّر ← «Wireless debugging»: فعّله،
echo(    ثم اختر «Pair device with pairing code». تعرض الشاشة رمزاً من 6 أرقام وعنواناً.
set "PAIR_TRIES=0"
:pair_again
set /a "PAIR_TRIES+=1"
if %PAIR_TRIES% GTR 3 goto pair_give_up
echo(
echo(==^> Type the address shown under the code, like 192.168.1.50:37215
echo(    اكتب العنوان الظاهر تحت الرمز، مثل 192.168.1.50:37215
call :read_addr port "Pairing address / عنوان الإقران: "
if errorlevel 1 exit /b 1
set "PAIR_ADDR=%ADDR%"
call :info "Type the 6-digit code shown on the TV. / اكتب الرمز المكوّن من 6 أرقام الظاهر في الشاشة."
call :read_code
if errorlevel 1 exit /b 1
"%ADB%" pair %PAIR_ADDR% %CODE% <nul >"%TMPOUT%" 2>&1
for /f "usebackq delims=" %%l in ("%TMPOUT%") do echo(    %%l
findstr /i /c:"Successfully paired" "%TMPOUT%" >nul && goto paired
findstr /i /c:"unknown command" "%TMPOUT%" >nul && goto pair_old_adb
call :warn "Pairing failed: wrong code, or the pairing window on the TV was closed. Try again." "فشل الإقران: الرمز خاطئ أو أُغلقت نافذة الإقران في الشاشة. حاول مرة أخرى."
goto pair_again
:pair_give_up
call :warn "Pairing failed 3 times." "فشل الإقران 3 مرات. تأكد من الرمز ثم شغّل المثبّت من جديد."
exit /b 1
:pair_old_adb
call :warn "This adb is too old to pair: it needs Platform-Tools 30 or newer." "أداة adb هذه أقدم من أن تقرن: تحتاج Platform-Tools 30 أو أحدث. استخدم أداة المثبّت المرفقة."
exit /b 1
:paired
call :info "Paired. / تم الإقران."
rem The address Wireless debugging listens on, as found on the network by mDNS: any word of an
rem adb-tls-connect line that is the pairing IP followed by a port.
for /f "tokens=1 delims=:" %%i in ("%PAIR_ADDR%") do set "PAIR_IP=%%i"
set "FOUND_ADDR="
set "TRIES=0"
:discover_again
set /a "TRIES+=1"
"%ADB%" mdns services <nul 2>nul | findstr /i /c:"adb-tls-connect" >"%TMPOUT%"
for /f "usebackq tokens=1-5" %%a in ("%TMPOUT%") do for %%t in (%%a %%b %%c %%d %%e) do for /f "tokens=1,2 delims=:" %%i in ("%%t") do if "%%i"=="%PAIR_IP%" if not defined FOUND_ADDR set "FOUND_ADDR=%%i:%%j"
if defined FOUND_ADDR goto discovered
if %TRIES% GEQ 5 goto ask_connect_addr
ping -n 3 127.0.0.1 >nul
goto discover_again
:discovered
set "ADDR=%FOUND_ADDR%"
call :check_addr port
if "%ADDR_OK%"=="0" goto ask_connect_addr
set "SERIAL=%FOUND_ADDR%"
call :try_connect
if "%CONNECT_RESULT%"=="ok" exit /b 0
:ask_connect_addr
set "PAIR_TRIES=0"
:ask_connect_addr_again
set /a "PAIR_TRIES+=1"
if %PAIR_TRIES% GTR 3 goto connect_addr_give_up
echo(
echo(==^> Now type the address shown on the «Wireless debugging» screen, under «IP address ^& Port»
echo(    اكتب الآن العنوان الظاهر في شاشة «Wireless debugging» تحت «IP address ^& Port»
echo(    Example / مثال: 192.168.1.50:41234
call :read_addr port "Address / العنوان: "
if errorlevel 1 exit /b 1
set "SERIAL=%ADDR%"
call :try_connect
if "%CONNECT_RESULT%"=="ok" exit /b 0
if "%CONNECT_RESULT%"=="unauthorized" goto connect_unauthorized
if "%CONNECT_RESULT%"=="unreachable" goto connect_unreachable
call :warn "The TV refused this address. Type the address of the Wireless debugging screen, not the pairing one." "رفضت الشاشة هذا العنوان. اكتب عنوان شاشة «Wireless debugging» نفسها، لا عنوان الإقران."
goto ask_connect_addr_again
:connect_addr_give_up
call :warn "Could not connect to the Wireless debugging address." "تعذّر الاتصال بعنوان «Wireless debugging». شغّل المثبّت من جديد."
exit /b 1

rem ---------------------------------------------------------------- the TV
rem %1 = all or disabled, %2 = package. Sets FOUND=1 if "pm list packages" prints exactly
rem package:%2 (pm matches substrings: youtube.tv also lists youtube.tvkids). for /f, not
rem findstr /x: adb shell output ends lines with LF only.
:find_package
set "FOUND=0"
set "PM_FLAG="
if "%~1"=="disabled" set "PM_FLAG=-d"
"%ADB%" -s %SERIAL% shell pm list packages %PM_FLAG% %~2 <nul >"%TMPOUT%" 2>nul
for /f "usebackq delims=" %%l in ("%TMPOUT%") do if "%%l"=="package:%~2" set "FOUND=1"
exit /b 0

rem Says which TV this is (maker, model, Android version), warns when the device does not call itself a TV, and
rem sets LEGACY=1 on Android 7.1 and older (Fire OS 6). Returns 1 when its Android is older than Shasha's minimum.
:describe_tv
rem The TV's own shell builds the line, so its text never passes through cmd's parser here.
"%ADB%" -s %SERIAL% shell "echo $(getprop ro.product.manufacturer) $(getprop ro.product.model), Android $(getprop ro.build.version.release), API $(getprop ro.build.version.sdk)" <nul >"%TVINFO%" 2>nul
echo(
for /f "usebackq delims=" %%l in ("%TVINFO%") do echo(==^> The TV / الشاشة: %%l
set "TV_SDK="
"%ADB%" -s %SERIAL% shell getprop ro.build.version.sdk <nul >"%TMPOUT%" 2>nul
for /f "usebackq tokens=1" %%v in ("%TMPOUT%") do if not defined TV_SDK set "TV_SDK=%%v"
rem Digits only, else unknown.
if defined TV_SDK for /f "delims=0123456789" %%x in ("%TV_SDK%") do set "TV_SDK="
rem Android TV and Google TV declare leanback, every Fire TV declares amazon.hardware.fire_tv.
"%ADB%" -s %SERIAL% shell pm list features <nul >"%TMPOUT%" 2>nul
findstr /l /c:"feature:" "%TMPOUT%" >nul || goto describe_sdk
findstr /l /c:"feature:android.software.leanback" /c:"feature:android.hardware.type.television" /c:"feature:amazon.hardware.fire_tv" "%TMPOUT%" >nul && goto describe_sdk
call :warn "This device does not say it is a TV: no Android TV, Google TV or Fire TV feature." "هذا الجهاز لا يعرّف نفسه تلفازاً: لا ميزة Android TV ولا Google TV ولا Fire TV."
call :info "Shasha is made for a TV and its remote. Installing anyway; check the address if this is not your TV."
call :info "«شاشة» مصمَّم للتلفاز وريموته. سيُثبَّت على أي حال؛ تأكد من العنوان إن لم يكن هذا تلفازك."
:describe_sdk
if not defined TV_SDK exit /b 0
if %TV_SDK% LSS %MIN_SDK% goto describe_too_old
if %TV_SDK% LSS 26 set "LEGACY=1"
if "%LEGACY%"=="1" set "OPS=SYSTEM_ALERT_WINDOW GET_USAGE_STATS"
exit /b 0
:describe_too_old
call :too_old_help
exit /b 1

:too_old_help
call :warn "This TV's system is too old: Shasha needs Android 7.1 or newer, on Fire TV Fire OS 6 or newer." "نظام هذه الشاشة أقدم مما يحتاجه «شاشة»: أندرويد 7.1 أو أحدث، وفي Fire TV: Fire OS 6 أو أحدث."
exit /b 0

rem %1 = a package: sets YT_PKG when it is installed.
:find_youtube
call :find_package all %1
if "%FOUND%"=="1" set "YT_PKG=%1"
exit /b 0

rem %1 = a package: enabled again when it is installed. Sets UNDO_FAILED=1 when that fails.
:undo_youtube
call :find_package all %1
if "%FOUND%"=="0" exit /b 0
call :say "Re-enabling the official YouTube app ..." "إعادة تفعيل تطبيق يوتيوب الرسمي ..."
"%ADB%" -s %SERIAL% shell pm enable %1 <nul
if errorlevel 1 (
  set "UNDO_FAILED=1"
  call :warn "Could not re-enable YouTube, see above." "تعذّرت إعادة تفعيل يوتيوب، انظر الرسالة أعلاه."
  exit /b 1
)
set "YOUTUBE_STATUS=enabled / مفعّل"
exit /b 0

rem Android 7.1 and older (Fire OS 6) have no per-app permission to install: the TV-wide Unknown sources setting,
rem on Fire TV Developer options, Apps from Unknown Sources, lets Check for updates open the system installer.
rem %1 = 1 (on) or 0 (off). Sets OPS_FAILED=1 when it fails.
:unknown_sources_set
"%ADB%" -s %SERIAL% shell settings put secure install_non_market_apps %1 <nul
if errorlevel 1 (
  set "OPS_FAILED=1"
  call :warn "Could not change Unknown sources, see above." "تعذّر تغيير «مصادر غير معروفة»، انظر الرسالة أعلاه."
  exit /b 1
)
echo(    Unknown sources / مصادر غير معروفة: %1
exit /b 0

rem %1 = expected value, 1 or 0. Clears OPS_OK when Unknown sources differs.
:check_unknown_sources
"%ADB%" -s %SERIAL% shell settings get secure install_non_market_apps <nul >"%TMPOUT%" 2>&1
set "US_VALUE="
for /f "usebackq tokens=1" %%v in ("%TMPOUT%") do if not defined US_VALUE set "US_VALUE=%%v"
if "%US_VALUE%"=="%1" goto check_unknown_sources_ok
set "OPS_OK=0"
echo(    [!!] Unknown sources / مصادر غير معروفة:
type "%TMPOUT%"
exit /b 1
:check_unknown_sources_ok
echo(    [OK] Unknown sources / مصادر غير معروفة: %1
exit /b 0

rem %1 = op, %2 = allow or default
:appops_set
"%ADB%" -s %SERIAL% shell appops set %PKG% %1 %2 <nul
if errorlevel 1 (
  set "OPS_FAILED=1"
  call :warn "appops set %1 %2 failed, see above." "فشل تعديل الصلاحية %1، انظر الرسالة أعلاه."
  exit /b 1
)
echo(    %1: %2
exit /b 0

rem Clears OPS_OK when one of GUARD_PERMS is still granted, per dumpsys package.
:check_perms
"%ADB%" -s %SERIAL% shell dumpsys package %PKG% <nul >"%TMPOUT%" 2>&1
for %%p in (%GUARD_PERMS%) do call :check_perm %%p
exit /b 0
:check_perm
findstr /l /c:"%1: granted=true" "%TMPOUT%" >nul && goto check_perm_bad
echo(    [OK] %1: not granted
exit /b 0
:check_perm_bad
set "OPS_OK=0"
echo(    [!!] %1: granted=true
exit /b 1

rem %1 = op, %2 = expected mode, allow or default. Clears OPS_OK when it does not match.
rem Matches "OP: mode" anywhere in a line, like "Uid mode: OP: allow".
:check_op
"%ADB%" -s %SERIAL% shell appops get %PKG% %1 <nul >"%TMPOUT%" 2>&1
if "%2"=="allow" goto check_op_allow
findstr /l /i /c:"%1: allow" /c:"%1: ignore" /c:"%1: deny" "%TMPOUT%" >nul && goto check_op_bad
echo(    [OK] %1: default
exit /b 0
:check_op_allow
findstr /l /c:"%1: allow" "%TMPOUT%" >nul || goto check_op_bad
echo(    [OK] %1: allow
exit /b 0
:check_op_bad
set "OPS_OK=0"
echo(    [!!] %1:
type "%TMPOUT%"
exit /b 1
