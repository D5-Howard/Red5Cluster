echo off
:start
cls
color 0a
MODE con: COLS=60 LINES=20
echo.
echo  ===============================
echo   Please Select your work
echo  ===============================
echo.
echo  1. Build project
echo  2. Copy jar to all
echo. 3. Copy jar to origin
echo. 4. Copy jar to edge
echo  5. Leave
echo.
echo.
:cho
set choice=
set root=..\..\1.0.10\
set /p choice=  Please input your command:
IF NOT "%Choice%"=="" SET Choice=%Choice:~0,1%
if /i "%choice%"=="1" goto Build
if /i "%choice%"=="2" goto CopyAll
if /i "%choice%"=="3" goto CopyOrigin
if /i "%choice%"=="4" goto CopyEdge
if /i "%choice%"=="5" goto Leave
 
:Build
call build.bat
goto start
 
:CopyAll
del ..\red5-server-edge\plugins\cluster-1.0.8-M1.jar
del ..\red5-server\plugins\cluster-1.0.8-M1.jar
Copy .\target\cluster-1.0.8-M1.jar %root%red5-server-edge\plugins\
Copy .\target\cluster-1.0.8-M1.jar %root%red5-server\plugins\
goto start

:CopyOrigin
del ..\red5-server\plugins\cluster-1.0.8-M1.jar
Copy .\target\cluster-1.0.8-M1.jar %root%red5-server\plugins\
goto start

:CopyEdge
del ..\red5-server-edge\plugins\cluster-1.0.8-M1.jar
Copy .\target\cluster-1.0.8-M1.jar %root%red5-server-edge\plugins\
goto start

:Leave
exit