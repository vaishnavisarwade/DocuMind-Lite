param(
    [string]$Email = "test@example.com",
    [string]$Password = "password123",
    [int]$Workspace = 1,
    [int]$DelaySeconds = 12
)

$base = "http://localhost:8081"
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType "application/json" `
    -Body (@{ email = $Email; password = $Password } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

function Ask($q) {
    for ($attempt = 1; $attempt -le 2; $attempt++) {
        try {
            $r = Invoke-RestMethod -Method Post -Uri "$base/api/workspaces/$Workspace/ask" -Headers $headers `
                -ContentType "application/json" -Body (@{ question = $q } | ConvertTo-Json)
            return @{ Response = $r; Error = $null }
        } catch {
            $code = $null
            try { $code = [int]$_.Exception.Response.StatusCode } catch {}
            $err = "HTTP $code"
            if ($attempt -eq 1) { Start-Sleep -Seconds 25 } else { return @{ Response = $null; Error = $err } }
        }
    }
}

$lines = Get-Content "$PSScriptRoot\eval-questions.txt" | Where-Object { $_.Trim() -ne "" }
$results = @()

foreach ($line in $lines) {
    $parts = $line -split '\|', 2
    $q = $parts[0].Trim()
    $expect = $parts[1].Trim()

    $out = Ask $q
    $r = $out.Response
    $reason = ""

    if ($null -eq $r) {
        $ok = $false
        $reason = "request failed: " + $out.Error
    } elseif ($expect -eq "REFUSE") {
        $ok = (-not $r.answered)
        if (-not $ok) { $reason = "answered instead of refusing: " + $r.answer }
    } else {
        $ok = $r.answered -and ($r.answer -match [regex]::Escape($expect))
        if (-not $ok) { $reason = "got: " + $r.answer }
    }

    $type = if ($expect -eq "REFUSE") { "unanswerable" } else { "answerable" }
    $status = if ($ok) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1}" -f $status, $q)
    if (-not $ok) { Write-Host ("        " + $reason) -ForegroundColor Yellow }
    $results += [pscustomobject]@{ Type = $type; Question = $q; Expected = $expect; Result = $status; Reason = $reason }
    Start-Sleep -Seconds $DelaySeconds
}

$total = @($results).Count
$passed = @($results | Where-Object { $_.Result -eq "PASS" }).Count
$ans = @($results | Where-Object { $_.Type -eq "answerable" })
$unans = @($results | Where-Object { $_.Type -eq "unanswerable" })
$ansPass = @($ans | Where-Object { $_.Result -eq "PASS" }).Count
$unansPass = @($unans | Where-Object { $_.Result -eq "PASS" }).Count

Write-Host ""
Write-Host ("Overall accuracy:        {0}/{1} = {2:P0}" -f $passed, $total, ($passed / $total))
Write-Host ("Answerable correct:      {0}/{1}" -f $ansPass, $ans.Count)
Write-Host ("Correctly refused:       {0}/{1}" -f $unansPass, $unans.Count)

$results | Export-Csv "$PSScriptRoot\eval-results.csv" -NoTypeInformation
Write-Host "Saved eval-results.csv"