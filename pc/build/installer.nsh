; Libera o Fly no Firewall do Windows (só rede local e Tailscale, só rede privada) para o celular falar com o PC.
; Também remove a regra antiga chamada "Zarpa" (nome anterior do app).
!macro customInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Zarpa"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Fly"'
  nsExec::Exec 'netsh advfirewall firewall add rule name="Fly" dir=in action=allow program="$INSTDIR\${PRODUCT_FILENAME}.exe" protocol=TCP localport=47800 profile=private remoteip=LocalSubnet,100.64.0.0/10 enable=yes'
!macroend

!macro customUnInstall
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Fly"'
  nsExec::Exec 'netsh advfirewall firewall delete rule name="Zarpa"'
!macroend
