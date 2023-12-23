$csvFilePath = 'id_secretkey.csv'
$userData = Import-Csv -Path $csvFilePath
$ffmpegCommandTemplate = 'ffmpeg.exe -re -i "test.flv" -hide_banner -loglevel 48 -c copy -f flv rtmp://localhost:1935/{0}/{1}'

# 반복 실행
foreach ($user in $userData) {
    # 사용자 ID와 StreamKey를 이용하여 명령어 생성
    $currentCommand = $ffmpegCommandTemplate -f $user.ID -f $user.StreamKey
    Write-Host "Executing command for user: $($user.ID) with StreamKey: $($user.StreamKey)"
    Invoke-Expression -Command $currentCommand
}

Write-Host "Script execution completed."
