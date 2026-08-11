# End-to-end deploy of the banking stack onto a local kind cluster.
#
#     ./k8s/deploy.ps1                 # create if needed, build images, deploy everything
#     ./k8s/deploy.ps1 -SkipBuild      # redeploy manifests only (fast; no image rebuild)
#     ./k8s/deploy.ps1 -Only auth-service
#     ./k8s/deploy.ps1 -Recreate       # delete the cluster first and start clean
#     ./k8s/deploy.ps1 -Pull           # deploy CI's published images instead of building locally
#     ./k8s/deploy.ps1 -Pull -ImageTag sha-1a2b3c4
#
# Safe to re-run. Every step checks for what it is about to create, so a second run converges on
# the same state rather than erroring -- which matters because the usual reason to run this is
# "something is wrong and I want a known-good state back".

[CmdletBinding()]
param(
    # Skip `docker build` + `kind load`. Manifest-only changes do not need a new image, and the
    # build is by far the slowest part.
    [switch]$SkipBuild,

    # Delete and recreate the cluster. Throwing kind away is cheaper than repairing it -- and it is
    # the only way to change the host port mappings in kind-cluster.yaml.
    [switch]$Recreate,

    # Re-run create-secrets.ps1 even if the Secret already exists. Off by default: that script
    # generates NEW random values, and rotating JWT_SECRET invalidates every issued token, so an
    # accidental rotation on a routine redeploy would log everyone out for no reason.
    [switch]$RotateSecrets,

    # Restrict the build/deploy to one service (infrastructure is still ensured).
    [string]$Only,

    # Run the images CI published to GHCR rather than building anything locally. This is the path a
    # real host uses -- it has no local Docker daemon holding the images and no `kind load` to
    # side-load them with -- so exercising it here is how that path gets tested before the host
    # exists. Implies -SkipBuild.
    [switch]$Pull,

    # Which published tag -Pull deploys. Defaults to `main`, which moves.
    #
    # Prefer an immutable `sha-<short>` tag for anything long-lived. Two reasons, and the second is
    # the one that bites: a deployment pinned to a moving tag cannot be rolled back, because the
    # name of the image that was working no longer resolves to it; and because the manifests set
    # imagePullPolicy: IfNotPresent, a node that already holds SOME image called `:main` will keep
    # running it, so re-pushing the tag changes nothing until the node forgets. A sha tag has
    # neither problem -- a new tag is always absent, so it is always pulled.
    [string]$ImageTag = "main"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
$ns = "banking"
$clusterName = "banking"

# Where CI publishes. Lowercase because a Docker reference must be, while the GitHub owner is
# "EmKonGa" -- the same trap the workflow avoids by not interpolating ${{ github.repository }}.
$registry = "ghcr.io/emkonga/banking-system"

# The tag built locally is deliberately one CI never publishes. If `:dev` also existed in the
# registry, a service whose local build had failed or been pruned would quietly fall back to the
# registry copy, and the deploy would look successful while running code that was never built here.
# With an unpublished tag the same situation is an immediate, obvious ErrImagePull.
$localTag = "dev"

# --- the stack, in dependency order ------------------------------------------------------------
# Adding a service later is one row here plus its manifest; nothing else in this script changes.
# Workload is written as kind/name rather than just a name, because postgres is a StatefulSet and
# the rest are Deployments -- `rollout status` and `rollout restart` both need the kind spelled out.
$infra = @(
    @{ Manifest = "00-namespace.yaml"; Workload = $null },
    @{ Manifest = "05-config.yaml";    Workload = $null },
    # Timeouts are sized for a FIRST run on a fresh cluster, where these images do not exist on the
    # node and must come from Docker Hub -- postgres alone measured 3m33s including queue time, and
    # Kafka is several times larger. On later runs the images are already there and each of these
    # settles in seconds. Sizing these for the warm case makes the script fail on exactly the run
    # where nothing is actually wrong.
    @{ Manifest = "10-postgres.yaml";  Workload = "statefulset/postgres"; Timeout = 600 },
    @{ Manifest = "11-redis.yaml";     Workload = "deployment/redis";     Timeout = 600 },
    @{ Manifest = "12-kafka.yaml";     Workload = "statefulset/kafka";    Timeout = 900 }
)

# Pinned, not "latest". An add-on that silently upgrades itself on the next deploy is a change
# nobody made and nobody can bisect.
$ingressControllerManifest =
    "https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.13.0/deploy/static/provider/kind/deploy.yaml"

# Image is not a column here: it is derived from Name, the registry and the tag being deployed, so
# that the local build and the published artifact cannot drift apart by editing one of two lists.
$services = @(
    @{ Name = "auth-service";           Manifest = "20-auth-service.yaml";           Timeout = 600; Schema = "banking_auth" },
    @{ Name = "notification-service";   Manifest = "21-notification-service.yaml";   Timeout = 600; Schema = "banking_notification" },
    # Schema = $null means "owns no database", not "schema not decided yet". api-gateway is the
    # only service here with nothing to migrate, so the migrate Job is skipped for it entirely.
    @{ Name = "api-gateway";            Manifest = "22-api-gateway.yaml";            Timeout = 600; Schema = $null },
    @{ Name = "account-service";        Manifest = "23-account-service.yaml";        Timeout = 600; Schema = "banking_account" },
    @{ Name = "payment-service";        Manifest = "24-payment-service.yaml";        Timeout = 600; Schema = "banking_payment" },
    @{ Name = "reconciliation-service"; Manifest = "25-reconciliation-service.yaml"; Timeout = 600; Schema = "banking_reconciliation" }
)
# The whole stack now runs in the cluster. Order is not significant -- services reach each other by
# Service DNS and retry, so a caller deployed before its callee simply fails until the callee is
# ready, which is what readiness gating already handles.

# --- helpers -----------------------------------------------------------------------------------

function Write-Step([string]$text) {
    Write-Host ""
    Write-Host "==> $text" -ForegroundColor Cyan
}

# Native executables do not raise terminating errors, so $ErrorActionPreference does nothing for
# them. Every kubectl/docker/kind call has to have its exit code checked explicitly or the script
# happily continues past a failure and reports success.
function Assert-LastExit([string]$what) {
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

function Get-KindPath {
    $cmd = Get-Command kind -ErrorAction SilentlyContinue
    if ($null -ne $cmd) { return $cmd.Source }
    # winget installs to a versioned directory that is not added to PATH for the current shell.
    $found = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Kubernetes.kind_*\kind.exe" -ErrorAction SilentlyContinue |
             Select-Object -First 1
    if ($null -eq $found) { throw "kind not found. Install with: winget install Kubernetes.kind" }
    return $found.FullName
}

# Asking for a named resource that does not exist makes kubectl write to stderr, and in Windows
# PowerShell 5.1 redirecting a native command's stderr wraps each line in a NativeCommandError --
# which $ErrorActionPreference = "Stop" then turns into a thrown exception. So probe by LISTING
# (always exit 0, never stderr) and matching the name, rather than by getting it and catching.
function Test-K8sResource([string]$kind, [string]$name) {
    $ErrorActionPreference = "Continue"
    $names = kubectl -n $ns get $kind -o name
    if ($LASTEXITCODE -ne 0) { return $false }
    return (@($names) -match "/$([regex]::Escape($name))$").Count -gt 0
}

function Test-Deployment([string]$name) { return (Test-K8sResource "deployments" $name) }

# The full reference for a service's image. One function, so "what did we build" and "what does the
# cluster run" are the same string by construction rather than by two lists agreeing.
function Get-ImageRef([string]$name, [string]$tag) { return "$registry/${name}:$tag" }

# Takes a "kind/name" reference, as used in the $infra table above.
function Test-Workload([string]$ref) {
    $parts = $ref -split "/", 2
    return (Test-K8sResource "$($parts[0])s" $parts[1])
}

# Runs a service's Flyway migrations to completion via a one-shot Job, BEFORE that service's
# Deployment is applied -- so migration has exactly one path in the cluster (this Job) instead of
# racing inside every app pod's own startup. The app itself has SPRING_FLYWAY_ENABLED=false in its
# manifest; see the note there for why.
#
# The SQL files are the source of truth in src/main/resources/db/migration -- this does NOT keep a
# second copy under k8s/. Instead it re-syncs a ConfigMap from that directory on every run, so
# there is nothing to keep in sync by hand and no risk of the cluster migrating from stale SQL.
function Invoke-DbMigration([string]$name, [string]$schema) {
    Write-Host "    syncing $name migrations into a ConfigMap..."
    $migrationDir = "$root\$name\src\main\resources\db\migration"
    $cmName = "$name-migrations"
    $jobName = "$name-migrate"

    # Delete-then-create, NOT the usual `create --dry-run=client -o yaml | kubectl apply -f -`.
    #
    # That pipeline corrupts the SQL. Windows PowerShell 5.1 does not pipe bytes between two native
    # executables -- it decodes kubectl's stdout into a .NET string using [Console]::OutputEncoding
    # and re-encodes it for the next process. Any byte the console codepage cannot represent
    # becomes a literal question mark, so every migration comment containing an em-dash reached the
    # cluster as "???".
    #
    # The reason it went unnoticed for a day is the reason it is worth this comment: the damage
    # depends on the console codepage, so it round-trips cleanly in an interactive UTF-8 terminal
    # and mangles the file when the same script is driven non-interactively. "Works when I run it"
    # is not evidence here.
    #
    # It then fails in the worst possible way. The file keeps its LENGTH (one 3-byte em-dash
    # becomes three '?'), so nothing looks truncated, and Flyway only objects later and only
    # against a schema that already exists -- "Migration checksum mismatch for migration version
    # 3", pointing at a file that is perfectly correct on disk. Five migrations across four
    # services contain non-ASCII, so this was waiting for every service still to be deployed.
    #
    # kubectl reads --from-file straight from disk and sends the bytes to the API server, so with
    # no pipeline there is nothing to re-encode. Delete first because plain `create` errors if the
    # ConfigMap exists, and `apply` alone cannot build the key-per-file shape from a directory.
    # The gap between delete and create is safe: the Job that mounts it is created further down.
    #
    # To CHECK the result, mount the ConfigMap and hash it in-cluster. Do not compare
    # `kubectl get -o json` output against the file: that output is itself re-encoded on Windows
    # and shows mojibake for content that is byte-perfect in etcd. Verified 2026-08-07 -- the
    # mounted file matched the file on disk exactly while the JSON did not.
    $ErrorActionPreference = "Continue"
    kubectl -n $ns delete configmap $cmName --ignore-not-found | Out-Null
    $ErrorActionPreference = "Stop"

    kubectl -n $ns create configmap $cmName --from-file="$migrationDir"
    Assert-LastExit "sync configmap $cmName"

    # Jobs are immutable once created (the pod template can't be patched), and the ConfigMap this
    # one mounts may have just changed -- so delete any previous run before creating a fresh Job
    # rather than trying to update it. Same stderr trap as Test-K8sResource: deleting a Job that
    # doesn't exist yet must not be treated as an error.
    $ErrorActionPreference = "Continue"
    kubectl -n $ns delete job $jobName --ignore-not-found --wait=true | Out-Null
    $ErrorActionPreference = "Stop"

    $jobYaml = @"
apiVersion: batch/v1
kind: Job
metadata:
  name: $jobName
  namespace: $ns
spec:
  backoffLimit: 3
  ttlSecondsAfterFinished: 600
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: flyway
          # NOT the -alpine variant, and this is the one image in the stack where that is a
          # decision rather than a default. Every -alpine tag in the Flyway 10 line is published
          # linux/amd64 ONLY -- verified with `docker manifest inspect`: 10-alpine and
          # 10.22-alpine list amd64 alone, while plain `10` carries amd64, arm and arm64. The
          # target host for the public deployment is Oracle A1, which is aarch64, so 10-alpine
          # would have failed there with an exec-format error at the one step every service
          # depends on -- and only after the cluster was already provisioned.
          #
          # `11-alpine` is also multi-arch and would keep the smaller image, but it is a major
          # bump on the tool that owns flyway_schema_history, and these databases already hold
          # v10 history rows. Staying on 10 keeps the checksum algorithm identical; a mismatch
          # there reads as "Migration checksum mismatch" against a file that is perfectly correct
          # on disk, which has already cost a session here once for an unrelated reason. Revisit
          # 11-alpine if the larger pull becomes a problem.
          image: flyway/flyway:10
          args: ["-connectRetries=10", "migrate"]
          env:
            - name: FLYWAY_URL
              value: "jdbc:postgresql://postgres:5432/banking_db"
            - name: FLYWAY_USER
              valueFrom:
                configMapKeyRef: { name: banking-config, key: DB_USER }
            - name: FLYWAY_PASSWORD
              valueFrom:
                secretKeyRef: { name: banking-secrets, key: DB_PASSWORD }
            - name: FLYWAY_SCHEMAS
              value: "$schema"
            - name: FLYWAY_DEFAULT_SCHEMA
              value: "$schema"
            - name: FLYWAY_LOCATIONS
              value: "filesystem:/flyway/sql"
          volumeMounts:
            - name: sql
              mountPath: /flyway/sql
      volumes:
        - name: sql
          configMap:
            name: $cmName
"@
    $jobYaml | kubectl apply -f -
    Assert-LastExit "apply job $jobName"

    Write-Host "    waiting for $jobName to complete..."
    # 300s, not 120s: same cold-pull trap as postgres/kafka above. Measured 2m10s on a node that
    # had never pulled flyway/flyway before, almost all of it image pull -- the migrate step itself
    # ran in 117ms. Warm runs finish in seconds either way.
    kubectl -n $ns wait --for=condition=complete "job/$jobName" --timeout=300s
    Assert-LastExit "job $jobName did not complete -- check: kubectl -n $ns logs job/$jobName"
}

# Same stderr trap as above, from the other direction: `kind get clusters` reports "No kind
# clusters found." on STDERR when there are none. That is the normal empty answer, not an error --
# but redirecting it raises NativeCommandError and aborts the script before it can create the
# cluster it was run to create. Contained here, with the error preference relaxed for this scope.
function Get-KindClusters {
    $ErrorActionPreference = "Continue"
    $out = & $kind get clusters 2>&1
    return @($out |
        ForEach-Object { "$_".Trim() } |
        Where-Object { $_ -and ($_ -notmatch "No kind clusters found") })
}

# --- 0. prerequisites --------------------------------------------------------------------------

Write-Step "Checking prerequisites"
$null = docker info --format "{{.ServerVersion}}"
if ($LASTEXITCODE -ne 0) { throw "Docker is not running. Start Docker Desktop and retry." }
$kind = Get-KindPath
Write-Host "    docker: ok"
Write-Host "    kind:   $kind"

# --- 1. cluster --------------------------------------------------------------------------------

$hasCluster = ((Get-KindClusters) -contains $clusterName)

if ($Recreate -and $hasCluster) {
    Write-Step "Deleting cluster '$clusterName'"
    & $kind delete cluster --name $clusterName
    Assert-LastExit "kind delete cluster"
    $hasCluster = $false
}

if (-not $hasCluster) {
    Write-Step "Creating cluster '$clusterName' (~1 minute)"
    & $kind create cluster --config "$root\k8s\kind-cluster.yaml"
    Assert-LastExit "kind create cluster"
} else {
    Write-Step "Cluster '$clusterName' already exists - reusing it"
}

# kubectl talks to whichever context is current, and Docker Desktop ships its own. Deploying into
# the wrong cluster is a genuinely easy mistake, so fail loudly instead of guessing.
$ctx = (kubectl config current-context).Trim()
if ($ctx -ne "kind-$clusterName") {
    throw "kubectl context is '$ctx', expected 'kind-$clusterName'. Switch with: kubectl config use-context kind-$clusterName"
}
Write-Host "    context: $ctx"

# --- 2. secrets --------------------------------------------------------------------------------
# Before any Deployment: a pod referencing a missing Secret sits in ContainerCreating indefinitely
# rather than failing with a useful message.

$hasSecret = Test-K8sResource "secrets" "banking-secrets"

if ($RotateSecrets -or -not $hasSecret) {
    Write-Step "Creating secrets"

    # Which workloads already exist matters. envFrom injects values at container start, so a pod
    # running now holds whatever the Secret said when it started -- it keeps working even if the
    # Secret is later deleted, and it keeps the OLD values even after new ones are generated. Left
    # alone, that is a landmine: the pod runs fine until something restarts it, and only then does
    # it fail (CreateContainerConfigError if the Secret is missing, or an auth error against
    # Postgres/Redis if the values have moved on).
    $preExisting = @()
    foreach ($item in $infra) {
        if ($null -ne $item.Workload -and (Test-Workload $item.Workload)) { $preExisting += $item.Workload }
    }
    foreach ($s in $services) {
        if (Test-Deployment $s.Name) { $preExisting += "deployment/$($s.Name)" }
    }

    & "$root\k8s\create-secrets.ps1"

    # Postgres needs its password changed IN THE DATABASE, and this is the step that is easy to
    # miss. POSTGRES_PASSWORD is applied by initdb, and initdb only runs on an empty PGDATA. On the
    # old emptyDir that was every restart, so rotation "worked" by destroying and re-creating the
    # database. On the PVC it ran exactly once, ever -- so from here on, rotating the Secret changes
    # only what the APPLICATIONS present. Left alone, every service would come back holding a
    # password Postgres has never heard of, and the failure would not appear until the next restart.
    if ($preExisting -contains "statefulset/postgres") {
        Write-Host "    Postgres data is persistent - applying the new password with ALTER USER..."
        $dbUser = kubectl -n $ns get configmap banking-config -o jsonpath="{.data.DB_USER}"
        Assert-LastExit "read DB_USER"
        $encoded = kubectl -n $ns get secret banking-secrets -o jsonpath="{.data.DB_PASSWORD}"
        Assert-LastExit "read DB_PASSWORD"
        $dbPass = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($encoded))
        # Over the container's UNIX socket, where initdb's default pg_hba line is `local all all
        # trust`. That is the point: it does not need the OLD password, which the cluster no longer
        # has anywhere. The value is base64, so it contains no quote that could break out of the
        # literal. ON_ERROR_STOP makes psql exit non-zero instead of reporting a failed ALTER on
        # stdout and exiting 0.
        kubectl -n $ns exec statefulset/postgres -- psql -U $dbUser -d banking_db -v ON_ERROR_STOP=1 `
            -c "ALTER USER `"$dbUser`" WITH PASSWORD '$dbPass'"
        Assert-LastExit "ALTER USER $dbUser"
    }

    # Redis is on a PVC too, but needs no equivalent step: --requirepass is a startup argument read
    # from the Secret on every boot, not state written into the volume once. The rollout restart
    # below is the whole of its rotation. Worth stating, because "it has a PVC" is what makes the
    # Postgres case surprising, and Redis has one now as well.

    if ($preExisting.Count -gt 0) {
        Write-Host ""
        Write-Host "    New credentials generated while workloads were already running." -ForegroundColor Yellow
        Write-Host "    Restarting all of them so every component agrees on the same values." -ForegroundColor Yellow
        foreach ($w in $preExisting) {
            kubectl -n $ns rollout restart $w
            Assert-LastExit "rollout restart $w"
        }
    }
} else {
    Write-Step "Secrets already present - leaving them alone (use -RotateSecrets to regenerate)"
}

# --- 3. config + infrastructure ----------------------------------------------------------------

Write-Step "Applying config and infrastructure"

# Kafka was a Deployment until it moved to a StatefulSet on a PVC. Both select `app: kafka`, so a
# leftover Deployment does not conflict with the StatefulSet -- it quietly coexists, and the
# ClusterIP Service round-robins between a broker holding the data and one holding none. Consumers
# would then see a topic that has messages on some connections and not others.
#
# `kubectl apply` cannot clean this up: the old object is a different kind under a different name in
# the API, so nothing about applying the new manifest implies deleting it. Explicit, and idempotent
# after the first run.
# Probed with Test-Deployment rather than `kubectl get deployment kafka 2>$null`: PS 5.1 wraps a
# native command's stderr in a NativeCommandError and throws under $ErrorActionPreference = "Stop",
# so probing for an ABSENT resource by name is exactly the thing that fails. Test-K8sResource lists
# and matches instead. This script has been bitten by that twice already.
if (Test-Deployment "kafka") {
    Write-Host "    removing the pre-StatefulSet kafka Deployment (its emptyDir holds nothing to keep)"
    kubectl -n $ns delete deployment kafka --wait=true
    Assert-LastExit "delete legacy kafka Deployment"
}

foreach ($item in $infra) {
    kubectl apply -f "$root\k8s\$($item.Manifest)"
    Assert-LastExit "kubectl apply $($item.Manifest)"
}

foreach ($item in $infra) {
    if ($null -ne $item.Workload) {
        Write-Host "    waiting for $($item.Workload)..."
        kubectl -n $ns rollout status $item.Workload --timeout="$($item.Timeout)s"
        Assert-LastExit "rollout status $($item.Workload)"
    }
}

# --- 3b. ingress controller --------------------------------------------------------------------
# Installed from upstream rather than vendored: it is a cluster add-on, not part of this
# application, and pinning the version here is enough to make the deploy reproducible.
#
# The `provider/kind` variant specifically. It differs from the generic one in exactly the ways
# that matter on a single-node kind cluster: the controller runs as a DaemonSet-like deployment
# pinned by `nodeSelector: ingress-ready=true` (the label kind-cluster.yaml sets) and uses
# hostPort 80/443 rather than a LoadBalancer Service -- because nothing here provisions external
# load balancers, so the generic manifest would sit at <pending> forever.
#
# Idempotent: re-applying an unchanged manifest is a no-op, so this runs on every deploy.

Write-Step "Installing the ingress-nginx controller"
kubectl apply -f $ingressControllerManifest
Assert-LastExit "kubectl apply ingress-nginx"

# The admission webhook rejects Ingress resources until its certificate Job has run, so applying
# 30-ingress.yaml before this is ready fails with "no endpoints available for service
# ingress-nginx-controller-admission" -- a confusing error for a manifest that is perfectly valid.
Write-Host "    waiting for the controller to be ready..."
kubectl -n ingress-nginx wait --for=condition=Ready pod `
    -l app.kubernetes.io/component=controller --timeout=300s
Assert-LastExit "wait for ingress-nginx controller"

kubectl apply -f "$root\k8s\30-ingress.yaml"
Assert-LastExit "kubectl apply 30-ingress.yaml"

# --- 4. images ---------------------------------------------------------------------------------
# Two ways an image reaches the node, and they are the local and the remote story respectively.
#
# By default it is built here and side-loaded with `kind load docker-image`, because the cluster is
# a separate container with its own containerd -- it cannot see the host's Docker daemon, and
# nothing has pushed this build anywhere. imagePullPolicy: IfNotPresent in the manifests is what
# stops kubelet going to the registry for it.
#
# With -Pull the images come from GHCR, published by CI as multi-arch manifest lists. That is the
# only mechanism available on a real host: `kind load` has no equivalent there, and building on the
# host itself means installing a toolchain and burning its RAM on six Maven builds. Running it here
# is how the remote path gets tested while the failures are still cheap to fix.

$targets = $services
if ($Only) {
    $targets = $services | Where-Object { $_.Name -eq $Only }
    if ($targets.Count -eq 0) { throw "Unknown service '$Only'. Known: $($services.Name -join ', ')" }
}

# -Pull is not "build, then also pull" -- it is the other source for the same images.
$buildImages = -not ($SkipBuild -or $Pull)
$deployTag = if ($Pull) { $ImageTag } else { $localTag }

if ($Pull) {
    Write-Step "Deploying published images: $registry/<service>:$deployTag"
    Write-Host "    nothing is built locally; kubelet pulls from GHCR"
} elseif ($SkipBuild) {
    Write-Step "Skipping image build (-SkipBuild)"
    # Worth saying out loud, because it has cost a session: -SkipBuild skips `kind load` too, so an
    # image built by hand outside this script is still absent from the node and the rollout will
    # time out in ImagePullBackOff.
} else {
    foreach ($s in $targets) {
        Write-Step "Building $($s.Name)"
        $image = Get-ImageRef $s.Name $localTag
        # Build context is the repo root, not the service directory: the Dockerfiles need the
        # parent POM and the shared banking-common / banking-events modules.
        docker build -t $image -f "$root\$($s.Name)\Dockerfile" $root
        Assert-LastExit "docker build $($s.Name)"

        Write-Host "    loading into cluster..."
        & $kind load docker-image $image --name $clusterName
        Assert-LastExit "kind load $image"
    }
}

# --- 5. services -------------------------------------------------------------------------------

foreach ($s in $targets) {
    Write-Step "Deploying $($s.Name)"

    # Whether the Deployment already existed decides if a restart is needed below, so check first.
    $existed = Test-Deployment $s.Name

    if ($null -ne $s.Schema) {
        Invoke-DbMigration $s.Name $s.Schema
    } else {
        Write-Host "    no schema - skipping migration Job"
    }

    kubectl apply -f "$root\k8s\$($s.Manifest)"
    Assert-LastExit "kubectl apply $($s.Manifest)"

    # The manifest names the local :dev tag, so deploying a published one is a deliberate override
    # of what was just applied. `set image` rather than a templated manifest: the manifests stay
    # plain YAML that `kubectl apply -f` can read directly, which is what lets this script keep
    # applying them one at a time in dependency order with a wait between each.
    #
    # It is idempotent -- if the deployment already runs this exact reference, kubectl patches
    # nothing and no rollout happens, which is the correct outcome for redeploying the same tag.
    if ($Pull) {
        $image = Get-ImageRef $s.Name $deployTag
        Write-Host "    pinning image to $image"
        kubectl -n $ns set image "deployment/$($s.Name)" "$($s.Name)=$image"
        Assert-LastExit "set image $($s.Name)"
    }

    # The image tag never changes, so a rebuilt :dev image is invisible to Kubernetes -- `apply`
    # sees an identical pod template and does nothing. An explicit restart is what actually rolls
    # the new bits out. Not needed under -Pull: `set image` above already changed the pod template
    # if the reference moved, and if it did not there is nothing new to roll out.
    if ($existed -and $buildImages) {
        kubectl -n $ns rollout restart "deployment/$($s.Name)"
        Assert-LastExit "rollout restart $($s.Name)"
    }

    kubectl -n $ns rollout status "deployment/$($s.Name)" --timeout="$($s.Timeout)s"
    Assert-LastExit "rollout status $($s.Name)"
}

# --- 6. summary --------------------------------------------------------------------------------

Write-Step "Done"
kubectl -n $ns get pods
Write-Host ""
kubectl -n $ns get svc
Write-Host ""
kubectl -n $ns get pvc

Write-Host ""
kubectl -n $ns get ingress

Write-Host ""
Write-Host "The stack is reachable from Windows at http://localhost:8000" -ForegroundColor Green
Write-Host "  curl http://localhost:8000/api/auth/register -Method POST ..."
Write-Host ""
Write-Host "8000, not 80: Hyper-V reserves low ports on Windows, so kind-cluster.yaml maps the"
Write-Host "node's 80 to the host's 8000. Inside the cluster nginx still listens on 80."
Write-Host ""
Write-Host "Only /api and /ws are published. /actuator is deliberately not -- api-gateway has no"
Write-Host "Spring Security, so its /actuator/prometheus answers unauthenticated."
Write-Host ""
Write-Host "Services are ClusterIP; to reach one directly, bypassing the gateway:" -ForegroundColor Green
Write-Host "  kubectl -n $ns port-forward svc/auth-service 8081:8081"
Write-Host "  kubectl -n $ns port-forward svc/notification-service 8084:8084"
Write-Host ""
Write-Host "Tear down:  ./k8s/teardown.ps1 -AppOnly    (namespace only - takes the Postgres PVC with it)"
Write-Host "            ./k8s/teardown.ps1            (the whole kind cluster)"
