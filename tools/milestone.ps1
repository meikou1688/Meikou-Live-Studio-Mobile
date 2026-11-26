$ErrorActionPreference="Stop"
& "$PSScriptRoot/prepare.ps1"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
python "$PSScriptRoot/test_transport.py"
$testResult=$LASTEXITCODE
python "$PSScriptRoot/backup.py"
if ($LASTEXITCODE) { exit $LASTEXITCODE }
exit $testResult
