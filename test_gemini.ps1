# Test-Script: sendet genau dieselbe Anfrage wie die App an Gemini
# Aufruf: .\test_gemini.ps1 -ApiKey "dein_key" -ImagePath "C:\pfad\zum\bild.jpg"

param(
    [Parameter(Mandatory=$true)] [string]$ApiKey,
    [Parameter(Mandatory=$true)] [string]$ImagePath
)

$imageBytes = [System.IO.File]::ReadAllBytes($ImagePath)
$base64 = [Convert]::ToBase64String($imageBytes)

$systemText = "You are a precise OCR system for reading electronic displays. " +
              "Analyze the image carefully. The image pixels are already correctly oriented. " +
              "Do not attempt to mentally re-rotate the image. " +
              "Read numbers and text with maximum accuracy. " +
              "Never guess or hallucinate values. " +
              "Return only what is explicitly visible on the display."

$userText = "What values are shown on this body scale display?" + [char]10 +
            "Write exactly three lines, nothing else:" + [char]10 +
            "weight=<number>" + [char]10 +
            "fat=<number>" + [char]10 +
            "water=<number>" + [char]10 +
            "Replace <number> with the actual value from the display. Use a dot as decimal separator."

$body = @{
    systemInstruction = @{
        parts = @(@{ text = $systemText })
    }
    contents = @(@{
        parts = @(
            @{ inline_data = @{ mime_type = "image/jpeg"; data = $base64 } },
            @{ text = $userText }
        )
    })
    generationConfig = @{
        temperature     = 0.0
        maxOutputTokens = 1024
        mediaResolution = "MEDIA_RESOLUTION_HIGH"
    }
} | ConvertTo-Json -Depth 10

$url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.6-flash:generateContent?key=$ApiKey"

Write-Host "Sende Anfrage an Gemini..." -ForegroundColor Cyan
Write-Host ("Bild: " + $ImagePath + " (" + [math]::Round($imageBytes.Length / 1024) + " KB)") -ForegroundColor Gray

try {
    $response = Invoke-RestMethod -Uri $url -Method Post -Body $body -ContentType "application/json; charset=utf-8"

    Write-Host ""
    Write-Host "Rohantwort (zur Diagnose):" -ForegroundColor Yellow
    Write-Host ($response | ConvertTo-Json -Depth 10)

    if ($null -eq $response.candidates -or $response.candidates.Count -eq 0) {
        Write-Host ""
        Write-Host "Keine candidates in der Antwort." -ForegroundColor Red
        if ($response.promptFeedback) {
            Write-Host ("Blockierungsgrund: " + $response.promptFeedback.blockReason)
        }
    } else {
        $candidate = $response.candidates[0]
        Write-Host ""
        Write-Host ("Finish Reason: " + $candidate.finishReason) -ForegroundColor Gray
        if ($null -ne $candidate.content) {
            $text = $candidate.content.parts[0].text
            Write-Host ""
            Write-Host "Gemini Antwort:" -ForegroundColor Green
            Write-Host $text
        } else {
            Write-Host "content ist leer (Safety-Block oder Quota)." -ForegroundColor Red
        }
        Write-Host ""
        Write-Host "Token-Verbrauch:" -ForegroundColor Gray
        Write-Host ("  Prompt:  " + $response.usageMetadata.promptTokenCount)
        Write-Host ("  Antwort: " + $response.usageMetadata.candidatesTokenCount)
    }
} catch {
    Write-Host ""
    Write-Host "HTTP-Fehler:" -ForegroundColor Red
    Write-Host $_.Exception.Message
    if ($_.ErrorDetails.Message) {
        try {
            $err = $_.ErrorDetails.Message | ConvertFrom-Json
            Write-Host ("Code:    " + $err.error.code)
            Write-Host ("Status:  " + $err.error.status)
            Write-Host ("Message: " + $err.error.message)
        } catch {
            Write-Host $_.ErrorDetails.Message
        }
    }
}
