@echo off
rem Builds a disk image holding the OS and boots it - the Windows counterpart of aeon.sh.
rem   aeon-os\aeon.cmd [image] [init|boot|smp_boot]      (default: aeon.img, init)
rem   set SCREEN=1       a text-mode display instead of the serial console
rem   set CORES=2        number of cores (default 4: the shell and three for background jobs)
rem   set SHARE=folder   the folder shown as host\ (default: share)
rem Needs JDK 21 and "mvn -DskipTests compile" run once.
setlocal
cd /d "%~dp0\.."
set "IMG=%~1"
if "%IMG%"=="" set "IMG=aeon.img"
set "BOOT=%~2"
if "%BOOT%"=="" set "BOOT=init"
if "%CORES%"=="" set "CORES=4"
if "%SHARE%"=="" set "SHARE=share"
if not exist "%SHARE%" mkdir "%SHARE%"
set "CP=bl0jv2-cli\target\classes;bl0jv2-runtime\target\classes;bl0jv2-compiler\target\classes;bl0jv2-common\target\classes"
set "DISPLAY_FLAG="
if not "%SCREEN%"=="" set "DISPLAY_FLAG=--display"
java -cp "%CP%" bl0.bl0jv2.cli.Bl0jv2_CLI -c -e -k -n %CORES% --disk "%IMG%" --disk-sectors 4096 --shared aeon-os/libs.txt ^
  --disk-put aeon-os/bin:bin ^
  --disk-put aeon-os/shell.bl0:sbin/shell.bl0c ^
  --disk-put aeon-os/child_hello.bl0:sbin/child_hello.bl0c ^
  --disk-put aeon-os/child_crash.bl0:sbin/child_crash.bl0c ^
  %DISPLAY_FLAG% --bridge-outbound --bridge-fs "%SHARE%" ^
  aeon-os/%BOOT%.bl0
endlocal
