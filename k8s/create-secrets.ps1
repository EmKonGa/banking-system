# Creates the `banking-secrets` Secret with freshly generated credentials.
#
# Run once per cluster, before applying the service manifests:
#     ./k8s/create-secrets.ps1
#
# Nothing here is written to disk or to git -- the values exist only in the cluster. That is the
# point: a Secret manifest committed to the repo publishes the credential to everyone with repo
# access, and to git history permanently, regardless of how strong the value is.
#
# Re-running rotates every secret. Note that JWT_SECRET is shared by all services (they verify
# each other's tokens without calling auth-service), so rotating it invalidates every issued token
# and every service must be restarted to pick up the new value.

$ErrorActionPreference = "Stop"

function New-RandomSecret([int]$Bytes = 48) {
    $buffer = New-Object byte[] $Bytes
    # RandomNumberGenerator::Create() rather than ::Fill() -- Fill is .NET Core only, and Windows
    # PowerShell 5.1 runs on .NET Framework, where it does not exist. Create() works on both.
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($buffer) } finally { $rng.Dispose() }
    # Base64 of 48 bytes is 64 chars, comfortably over the 32-byte minimum HS256 requires.
    return [Convert]::ToBase64String($buffer)
}

kubectl create namespace banking --dry-run=client -o yaml | kubectl apply -f -

# --dry-run=client | kubectl apply makes this idempotent. `kubectl create secret` alone fails if
# the Secret already exists, which would make re-running this script an error rather than a
# rotation.
kubectl create secret generic banking-secrets `
    --namespace banking `
    --from-literal=DB_PASSWORD=$(New-RandomSecret 24) `
    --from-literal=REDIS_PASSWORD=$(New-RandomSecret 24) `
    --from-literal=JWT_SECRET=$(New-RandomSecret 48) `
    --from-literal=INTERNAL_SECRET=$(New-RandomSecret 32) `
    --dry-run=client -o yaml | kubectl apply -f -

Write-Host ""
Write-Host "banking-secrets created. Inspect keys (not values) with:" -ForegroundColor Green
Write-Host "  kubectl -n banking describe secret banking-secrets"
