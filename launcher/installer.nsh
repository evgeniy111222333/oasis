!macro preInit
  !ifndef BUILD_UNINSTALLER
    ; Launcher 1.0.6 starts setup as its child and then exits, which also
    ; terminates a child installer that is still waiting. If an installed
    ; launcher is running, switch to silent mode immediately: the standard
    ; NSIS CHECK_APP_RUNNING flow will close the old process first, allowing
    ; setup to survive and replace it. First-time installs stay interactive.
    !insertmacro FIND_PROCESS "${APP_EXECUTABLE_FILENAME}" $R0
    ${If} $R0 == 0
      SetSilent silent
    ${EndIf}
  !endif
!macroend
