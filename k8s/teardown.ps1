# Tears down the banking stack.
#
#     ./k8s/teardown.ps1             # delete the whole kind cluster (default)
#     ./k8s/teardown.ps1 -AppOnly    # delete only the banking namespace, keep the cluster
#     ./k8s/teardown.ps1 -Force      # skip the confirmation prompt
#
# The counterpart to deploy.ps1, and the same "safe to re-run" rule applies: tearing down something
# that is already gone reports it and exits 0 rather than failing, because the usual reason to run
# this is to get back to a known state.

[CmdletBinding()]
param(
    # Delete the namespace only. Much cheaper to undo: the node keeps its image store, so the next
    # deploy skips the cold pulls that cost minutes (postgres measured 3m33s on a fresh cluster).
    [switch]$AppOnly,

    # Do not ask. For scripted use; interactively you want the prompt, because neither of these is
    # undoable and the cluster variant takes the Secret with it.
    [switch]$Force
)

$ErrorActionPreference = "Stop"
$ns = "banking"
$clusterName = "banking"

function Write-Step([string]$text) {
    Write-Host ""
    Write-Host "==> $text" -ForegroundColor Cyan
}

# Native executables do not raise terminating errors, so $ErrorActionPreference does nothing for
# them -- every exit code has to be checked explicitly.
function Assert-LastExit([string]$what) {
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

# Mirrors deploy.ps1. Duplicated rather than shared: it is a handful of lines that only change if
# kind's install location does, and a third dot-sourced file to hold them costs more than it saves.
function Get-KindPath {
    $cmd = Get-Command kind -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    # winget installs to a versioned directory that is not added to PATH for the current shell.
    $found = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Kubernetes.kind_*\kind.exe" -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($null -eq $found) { throw "kind not found. Install with: winget install Kubernetes.kind" }
    return $found.FullName
}

# `kind get clusters` writes "No kind clusters found." to STDERR when there are none. That is the
# normal empty answer, not an error -- but in Windows PowerShell 5.1 redirecting a native command's
# stderr wraps each line in a NativeCommandError, which $ErrorActionPreference = "Stop" then throws
# on. Contained here, with the preference relaxed for this scope only.
function Get-KindClusters {
    $ErrorActionPreference = "Continue"
    $out = & $kind get clusters 2>&1
    return @($out |
        ForEach-Object { "$_".Trim() } |
        Where-Object { $_ -and ($_ -notmatch "No kind clusters found") })
}

function Confirm-Or-Exit([string]$prompt) {
    if ($Force) { return }
    Write-Host ""
    $answer = Read-Host "$prompt [y/N]"
    if ($answer -notmatch '^(y|yes)$') {
        Write-Host "Aborted - nothing was deleted." -ForegroundColor Yellow
        exit 0
    }
}

$kind = Get-KindPath

# --- app only ----------------------------------------------------------------------------------

if ($AppOnly) {
    Write-Step "Deleting namespace '$ns' (cluster '$clusterName' stays)"
    Write-Host "    This removes every workload, Service, ConfigMap and the banking-secrets Secret."
    # Postgres survives a pod delete now that it is a StatefulSet on a PVC -- but not this. A PVC is
    # a namespaced object, so deleting the namespace deletes data-postgres-0 and the local-path
    # directory backing it. Deleting the STATEFULSET alone would keep the claim (that is the point
    # of volumeClaimTemplates); deleting what contains it does not.
    Write-Host "    Postgres data goes with it: the PVC data-postgres-0 is in this namespace."
    Write-Host "    The next deploy regenerates secrets, so JWT_SECRET rotates and old tokens die."

    Confirm-Or-Exit "Delete namespace '$ns'?"

    # --ignore-not-found keeps a second run from erroring. The timeout matters because a namespace
    # whose resources have stuck finalizers will otherwise block here forever with no output.
    kubectl delete namespace $ns --ignore-not-found --timeout=120s
    Assert-LastExit "kubectl delete namespace $ns"

    Write-Step "Done"
    Write-Host "Cluster '$clusterName' is still running. Bring the app back with:" -ForegroundColor Green
    Write-Host "  ./k8s/deploy.ps1 -SkipBuild"
    exit 0
}

# --- whole cluster -----------------------------------------------------------------------------

if ((Get-KindClusters) -notcontains $clusterName) {
    Write-Step "Cluster '$clusterName' does not exist - nothing to do"
    exit 0
}

Write-Step "Deleting cluster '$clusterName'"
Write-Host "    This is not undoable. It takes with it:"
Write-Host "      - every workload and all Postgres data"
Write-Host "      - banking-secrets, including JWT_SECRET and the database password"
Write-Host "      - the node's own image store, so the next deploy re-pulls postgres, redis,"
Write-Host "        kafka and flyway from Docker Hub (minutes, not seconds) and re-runs kind load"
Write-Host ""
Write-Host "    Use -AppOnly instead if you only want to reset the application." -ForegroundColor Yellow

Confirm-Or-Exit "Delete cluster '$clusterName' and everything in it?"

& $kind delete cluster --name $clusterName
Assert-LastExit "kind delete cluster"

Write-Step "Done"
Write-Host "Rebuild everything from nothing with:" -ForegroundColor Green
Write-Host "  ./k8s/deploy.ps1"
