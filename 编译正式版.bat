@echo off
title Lyricon Build - Release
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build_release.ps1"
pause
