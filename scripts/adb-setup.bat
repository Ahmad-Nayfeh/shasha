@echo off
rem Shasha: installs the app on the TV over the network and grants the permissions that only
rem ADB can grant (SPEC section 4). See scripts\README.md.
rem   adb-setup.bat TV_IP[:port] [path\to\shasha.apk]
rem   adb-setup.bat --undo TV_IP[:port]
rem Double-click it (or drop the APK on it) to be asked for the TV address.
rem Keep this file UTF-8 WITHOUT BOM, with CRLF line endings. Lines before "chcp 65001" stay ASCII.
setlocal EnableExtensions DisableDelayedExpansion

rem Remember the console code page, then switch to UTF-8 so the Arabic lines print.
set "OLDCP="
for /f "tokens=2 delims=:." %%c in ('chcp') do set /a "OLDCP=%%c" >nul 2>&1
chcp 65001 >nul

set "PKG=com.ahmadnayfeh.shasha"
set "ACTIVITY=com.ahmadnayfeh.shasha/.MainActivity"
set "YT_PKG=com.google.android.youtube.tv"
rem SYSTEM_ALERT_WINDOW: the guard draws over other apps. GET_USAGE_STATS: it sees which app is
rem in front. REQUEST_INSTALL_PACKAGES: the in-app updater may open the system installer.
set "OPS=SYSTEM_ALERT_WINDOW GET_USAGE_STATS REQUEST_INSTALL_PACKAGES"
set "CONNECT_TIMEOUT=60"
set "SELF=%~f0"
set "SCRIPT_DIR=%~dp0"
set "TMPOUT=%TEMP%\shasha-adb-setup-%RANDOM%.txt"
set "UNDO=0"
set "TV="
set "APK="
set "INTERACTIVE=0"
set "EXITCODE=0"
set "OPS_OK=1"
set "OPS_FAILED=0"
set "YOUTUBE_STATUS=not installed / غير مثبّت"

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
  set "APK=%~1"
) else (
  if defined TV goto bad_usage
  set "TV=%~1"
)
shift
goto parse

:parsed
if not defined TV (
  set "INTERACTIVE=1"
  call :usage_text
  echo.
  set /p "TV=TV IP address / عنوان IP للشاشة: "
)
if not defined TV goto bad_usage
set "TV=%TV:"=%"
set "TV=%TV: =%"
if not defined TV goto bad_usage
set "SERIAL=%TV%"
if "%TV::=%"=="%TV%" set "SERIAL=%TV%:5555"

rem ---------------------------------------------------------------- adb
call :say "Checking adb ..." "التحقق من وجود adb ..."
set "ADB="
for /f "delims=" %%p in ('where adb.exe 2^>nul') do if not defined ADB set "ADB=%%p"
if not defined ADB if exist "%SCRIPT_DIR%platform-tools\adb.exe" set "ADB=%SCRIPT_DIR%platform-tools\adb.exe"
if not defined ADB if exist "%CD%\platform-tools\adb.exe" set "ADB=%CD%\platform-tools\adb.exe"
if not defined ADB if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB goto no_adb
"%ADB%" version <nul >"%TMPOUT%" 2>&1
set "ADB_VERSION="
for /f "usebackq delims=" %%v in ("%TMPOUT%") do if not defined ADB_VERSION set "ADB_VERSION=%%v"
echo(    "%ADB%"
echo(    %ADB_VERSION%

if "%UNDO%"=="1" goto undo

rem ---------------------------------------------------------------- setup
if defined APK goto apk_chosen
for /f "delims=" %%f in ('dir /b /a-d /o-d "%SCRIPT_DIR%shasha-*.apk" 2^>nul') do if not defined APK set "APK=%SCRIPT_DIR%%%f"
if defined APK goto apk_chosen
for /f "delims=" %%f in ('dir /b /a-d /o-d "%CD%\shasha-*.apk" 2^>nul') do if not defined APK set "APK=%CD%\%%f"
if defined APK goto apk_chosen
call :warn "No APK given and no shasha-*.apk next to this script or in the current folder." "لم يُحدَّد ملف APK ولا يوجد ملف shasha-*.apk بجانب السكربت أو في المجلد الحالي. حمّله من GitHub Releases."
goto fail

:apk_chosen
if not exist "%APK%" (
  call :warn "APK not found: %APK%" "ملف APK غير موجود."
  goto fail
)
echo(    APK: "%APK%"

call :connect
if errorlevel 1 goto fail

call :say "Installing the APK ..." "تثبيت التطبيق على الشاشة، قد يستغرق دقيقة ..."
"%ADB%" -s %SERIAL% install -r -g "%APK%" <nul >"%TMPOUT%" 2>&1
if errorlevel 1 goto install_failed
rem Old adb versions exit 0 even when the install fails: require the "Success" line too.
findstr /l /b /c:"Success" "%TMPOUT%" >nul || goto install_failed
echo(    Success
goto grant

:install_failed
type "%TMPOUT%"
findstr /l /i /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" /c:"signatures do not match" "%TMPOUT%" >nul && goto install_other_key
findstr /l /i /c:"INSTALL_FAILED_VERSION_DOWNGRADE" "%TMPOUT%" >nul && goto install_downgrade
findstr /l /i /c:"NO_CERTIFICATES" /c:"no signatures" "%TMPOUT%" >nul && goto install_unsigned
call :warn "Installation failed, see the message above." "فشل التثبيت، انظر الرسالة أعلاه."
goto fail

:install_other_key
call :warn "The Shasha already on the TV was signed with a different key, an old or test build." "النسخة المثبّتة على الشاشة موقّعة بمفتاح آخر، بناء قديم أو تجريبي، فلا يمكن التحديث فوقها."
call :info "Nothing was changed. To replace it, uninstall it first. This DELETES its data:"
call :info "profiles, subscriptions, history. Export a backup in Shasha's settings first."
call :info "لم يتغيّر شيء. لاستبدالها يجب حذفها أولاً، وهذا يمسح بياناتها: الملفات والاشتراكات والسجل."
call :info "صدّر نسخة احتياطية من إعدادات التطبيق قبل الحذف إن احتجت إليها. أمر الحذف:"
echo(
echo(        "%ADB%" -s %SERIAL% uninstall %PKG%
echo(
call :info "Then run this script again. / ثم أعد تشغيل هذا السكربت."
goto fail

:install_downgrade
call :warn "The TV already has a newer Shasha than this APK." "النسخة على الشاشة أحدث من هذا الملف. حمّل أحدث ملف من صفحة GitHub Releases."
goto fail

:install_unsigned
call :warn "This APK is not signed, a CI build made without the signing key." "هذا الملف غير موقّع، بناء بلا مفتاح التوقيع. حمّل الملف من صفحة GitHub Releases."
goto fail

:grant
call :say "Granting permissions: display over apps, usage access, install updates ..." "منح الصلاحيات: العرض فوق التطبيقات، الوصول إلى الاستخدام، تثبيت التحديثات ..."
for %%o in (%OPS%) do call :appops_set %%o allow
if "%OPS_FAILED%"=="1" goto fail

rem ---------------------------------------------------------------- official YouTube app
"%ADB%" -s %SERIAL% shell pm list packages %YT_PKG% <nul >"%TMPOUT%" 2>nul
findstr /l /x /c:"package:%YT_PKG%" "%TMPOUT%" >nul || goto youtube_absent
"%ADB%" -s %SERIAL% shell pm list packages -d %YT_PKG% <nul >"%TMPOUT%" 2>nul
findstr /l /x /c:"package:%YT_PKG%" "%TMPOUT%" >nul && goto youtube_already_disabled
call :say "Disable the official YouTube app on the TV? It can be re-enabled any time." "هل تريد تعطيل تطبيق يوتيوب الرسمي على الشاشة؟ يمكن إعادته في أي وقت."
set "ANSWER="
set /p "ANSWER=    Disable YouTube? [y/N] "
if not defined ANSWER goto youtube_keep
set "ANSWER=%ANSWER:"=%"
if /i "%ANSWER%"=="y" goto youtube_disable
if /i "%ANSWER%"=="yes" goto youtube_disable
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
findstr /l /b /c:"Error" "%TMPOUT%" >nul && call :warn "Could not start the app; open it from the TV home screen." "تعذّر تشغيل التطبيق؛ افتحه يدوياً من الشاشة الرئيسية."

call :say "Verification" "التحقق"
set "OPS_OK=1"
for %%o in (%OPS%) do call :check_op %%o allow
set "OPS_TEXT=granted / ممنوحة"
if "%OPS_OK%"=="0" set "OPS_TEXT=INCOMPLETE / ناقصة"

call :say "Summary" "الملخص"
echo(    TV / الشاشة:              %SERIAL%
echo(    APK / الملف:               "%APK%"
echo(    Permissions / الصلاحيات:   %OPS_TEXT%
echo(    YouTube / يوتيوب الرسمي:  %YOUTUBE_STATUS%
echo(    Undo / للتراجع:            "%SELF%" --undo %SERIAL%
echo(
call :info "To double-check, open Shasha, Settings, Protection status on the TV."
call :info "للتأكد: افتح تطبيق شاشة، ثم الإعدادات، ثم حالة الحماية."
if "%OPS_OK%"=="0" goto fail
goto finish

rem ---------------------------------------------------------------- undo
:undo
if defined APK call :warn "--undo ignores the APK argument." "خيار --undo يتجاهل ملف APK."
call :connect
if errorlevel 1 goto fail
"%ADB%" -s %SERIAL% shell pm list packages %YT_PKG% <nul >"%TMPOUT%" 2>nul
findstr /l /x /c:"package:%YT_PKG%" "%TMPOUT%" >nul || goto undo_ops
call :say "Re-enabling the official YouTube app ..." "إعادة تفعيل تطبيق يوتيوب الرسمي ..."
"%ADB%" -s %SERIAL% shell pm enable %YT_PKG% <nul
if errorlevel 1 (
  call :warn "Could not re-enable YouTube, see above." "تعذّرت إعادة تفعيل يوتيوب، انظر الرسالة أعلاه."
  goto fail
)
set "YOUTUBE_STATUS=enabled / مفعّل"

:undo_ops
set "OPS_TEXT=app not installed / التطبيق غير مثبّت"
"%ADB%" -s %SERIAL% shell pm list packages %PKG% <nul >"%TMPOUT%" 2>nul
findstr /l /x /c:"package:%PKG%" "%TMPOUT%" >nul || goto undo_summary
call :say "Resetting Shasha's permissions to default ..." "إرجاع صلاحيات شاشة إلى الوضع الافتراضي ..."
for %%o in (%OPS%) do call :appops_set %%o default
if "%OPS_FAILED%"=="1" goto fail
call :say "Verification" "التحقق"
set "OPS_OK=1"
for %%o in (%OPS%) do call :check_op %%o default
set "OPS_TEXT=default / افتراضية"
if "%OPS_OK%"=="0" set "OPS_TEXT=CHECK ABOVE / راجع أعلاه"

:undo_summary
call :say "Summary" "الملخص"
echo(    TV / الشاشة:              %SERIAL%
echo(    Permissions / الصلاحيات:   %OPS_TEXT%
echo(    YouTube / يوتيوب الرسمي:  %YOUTUBE_STATUS%
call :info "Shasha stays installed. To remove it: / التطبيق باقٍ. لحذفه:"
echo(        "%ADB%" -s %SERIAL% uninstall %PKG%
if "%OPS_OK%"=="0" goto fail
goto finish

rem ---------------------------------------------------------------- endings
:no_adb
call :warn "adb was not found." "لم يُعثر على adb."
call :info "Download Android SDK Platform-Tools and unzip it next to this script, a folder named platform-tools:"
call :info "حمّل Platform-Tools وفك ضغطه بجانب هذا السكربت، في مجلد باسم platform-tools:"
call :info "  https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
call :info "  https://developer.android.com/tools/releases/platform-tools"
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
if exist "%TMPOUT%" del /q "%TMPOUT%" >nul 2>&1
if "%INTERACTIVE%"=="1" (
  echo.
  pause
)
if defined OLDCP chcp %OLDCP% >nul
exit /b %EXITCODE%

rem ================================================================ subroutines
rem Messages passed to say/warn/info: no double quotes, angle brackets, pipes, ampersands,
rem carets or percent signs (they would be parsed as syntax).

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
echo   adb-setup.bat TV_IP [path\to\shasha.apk]   install Shasha and grant its permissions
echo   adb-setup.bat --undo TV_IP                 re-enable YouTube and reset the permissions
echo TV_IP may carry a port, default 5555: 192.168.1.50 or 192.168.1.50:5555
echo Without an APK path: the newest shasha-*.apk next to this script, else in the current folder.
echo(
echo الاستخدام:
echo   adb-setup.bat TV_IP [مسار ملف APK]    تثبيت شاشة ومنح الصلاحيات
echo   adb-setup.bat --undo TV_IP            التراجع: إعادة يوتيوب وإلغاء الصلاحيات
exit /b 0

:connect
call :say "Connecting to %SERIAL% ..." "الاتصال بالشاشة %SERIAL% ..."
"%ADB%" start-server <nul >nul 2>&1
"%ADB%" connect %SERIAL% <nul
set "WAITED=0"
set "LAST_TRY=0"
set "HINTED=0"
:connect_loop
set "STATE="
"%ADB%" devices <nul >"%TMPOUT%" 2>nul
for /f "usebackq tokens=1,2" %%a in ("%TMPOUT%") do if /i "%%a"=="%SERIAL%" set "STATE=%%b"
if "%STATE%"=="device" goto connect_ok
if "%STATE%"=="unauthorized" goto connect_unauthorized
rem Not listed yet, or "offline": ask again every 6 s.
set /a "SINCE=WAITED-LAST_TRY"
if %SINCE% GEQ 6 (
  if "%STATE%"=="offline" "%ADB%" disconnect %SERIAL% <nul >nul 2>&1
  "%ADB%" connect %SERIAL% <nul >nul 2>&1
  set "LAST_TRY=%WAITED%"
)
goto connect_wait
:connect_unauthorized
if "%HINTED%"=="1" goto connect_wait
set "HINTED=1"
call :say "Look at the TV: it asks 'Allow USB debugging?'" "انظر إلى الشاشة: تظهر نافذة 'Allow USB debugging?' للسماح بتصحيح الأخطاء."
call :info "With the remote, tick 'Always allow from this computer' and press OK."
call :info "بالريموت: فعّل 'Always allow from this computer' أي السماح دائماً من هذا الحاسوب، ثم OK."
call :info "Waiting up to %CONNECT_TIMEOUT% s ... / بانتظار الموافقة حتى %CONNECT_TIMEOUT% ثانية ..."
:connect_wait
if %WAITED% GEQ %CONNECT_TIMEOUT% goto connect_failed
rem About 2 seconds; "timeout" fails when input is redirected, ping does not.
ping -n 3 127.0.0.1 >nul
set /a "WAITED+=2"
goto connect_loop
:connect_ok
call :info "Connected. / تم الاتصال."
exit /b 0
:connect_failed
if not defined STATE set "STATE=not found"
call :warn "Could not connect to %SERIAL%, state: %STATE%." "تعذّر الاتصال بالشاشة %SERIAL%."
call :info "- Is the TV on, on the same network, and is the IP right? Settings, Network and Internet."
call :info "  هل الشاشة تعمل وعلى الشبكة نفسها، والعنوان صحيح؟ الإعدادات، الشبكة والإنترنت."
call :info "- Developer options: USB debugging, and Network debugging if present, must be ON."
call :info "  يجب تفعيل خيارات المطوّر: تصحيح أخطاء USB، وتصحيح الأخطاء عبر الشبكة إن وُجد."
call :info "- Did you press OK on 'Allow USB debugging?' Then run the script again."
call :info "  هل ضغطت OK على نافذة السماح؟ ثم أعد تشغيل السكربت."
call :info "- Still stuck: run the command below, then try again. / إن استمرت المشكلة نفّذ الأمر التالي ثم أعد المحاولة:"
echo(        "%ADB%" kill-server
exit /b 1

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

rem %1 = op, %2 = expected mode (allow or default). Clears OPS_OK when it does not match.
:check_op
"%ADB%" -s %SERIAL% shell appops get %PKG% %1 <nul >"%TMPOUT%" 2>&1
if "%2"=="allow" goto check_op_allow
findstr /l /i /b /c:"%1: allow" /c:"%1: ignore" /c:"%1: deny" "%TMPOUT%" >nul && goto check_op_bad
echo(    [OK] %1: default
exit /b 0
:check_op_allow
findstr /l /b /c:"%1: allow" "%TMPOUT%" >nul || goto check_op_bad
echo(    [OK] %1: allow
exit /b 0
:check_op_bad
set "OPS_OK=0"
echo(    [!!] %1:
type "%TMPOUT%"
exit /b 1
