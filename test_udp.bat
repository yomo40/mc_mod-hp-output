@echo off
chcp 65001 >nul
echo ========================================
echo   HP Output UDP 监听器
echo   监听端口: 39571
echo   按 Ctrl+C 退出
echo ========================================
echo.

powershell -NoProfile -Command "$udp = New-Object System.Net.Sockets.UdpClient(39571); $ep = New-Object System.Net.IPEndPoint([System.Net.IPAddress]::Any, 0); Write-Host '正在监听 UDP 端口 39571...' -ForegroundColor Green; while ($true) { $data = $udp.Receive([ref]$ep); $msg = [System.Text.Encoding]::UTF8.GetString($data); $json = $msg | ConvertFrom-Json; $time = Get-Date -Format 'HH:mm:ss.fff'; if ($json.death) { Write-Host \"[$time] DEATH: $($json.source)\" -ForegroundColor Magenta } elseif ($null -ne $json.damage) { Write-Host \"[$time] DAMAGE: -$($json.damage) from '$($json.source)' | HP: $($json.health)/$($json.maxHealth) ($([math]::Round($json.percentage * 100))%%)\" -ForegroundColor Red } elseif ($null -ne $json.heal) { Write-Host \"[$time] HEAL: +$($json.heal) | HP: $($json.health)/$($json.maxHealth) ($([math]::Round($json.percentage * 100))%%)\" -ForegroundColor Green } else { Write-Host \"[$time] HP: $($json.health)/$($json.maxHealth) ($([math]::Round($json.percentage * 100))%%)\" -ForegroundColor Cyan } }"

pause
