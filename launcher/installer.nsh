!macro preInit
  !ifndef BUILD_UNINSTALLER
    ; Launcher 1.0.6 starts the setup before it exits and does not pass
    ; electron-builder's --updated flag. Give that legacy process enough
    ; time to close before NSIS checks/replaces the running application.
    ; Only force silent mode when an existing launcher initiated the setup;
    ; a normal first-time installer remains interactive.
    !insertmacro FIND_PROCESS "${APP_EXECUTABLE_FILENAME}" $R0
    ${If} $R0 == 0
      SetSilent silent
      Sleep 2500
    ${EndIf}
  !endif
!macroend
