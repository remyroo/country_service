# Local Kubernetes deployment

Run commands from the repository root. Prerequisites: Docker running, `kind`,
`kubectl`, the configured local `.env`, and enough Docker memory for Kubernetes,
MySQL, and two app pods (allow roughly 4 GiB or more). No Helm or ingress is needed.
The commands explicitly target a dedicated local cluster, never your current
production/staging context. This database is separate from Docker Compose.

## Deploy

Create the cluster once, using a separate kubeconfig:

```sh
kind create cluster --name country-service --kubeconfig /tmp/country-service-kubeconfig --wait 120s
export KUBECONFIG=/tmp/country-service-kubeconfig
kubectl --context kind-country-service cluster-info
kubectl --context kind-country-service get storageclass
```

If the cluster already exists, recover its kubeconfig instead of recreating it:

```sh
kind export kubeconfig --name country-service --kubeconfig /tmp/country-service-kubeconfig
export KUBECONFIG=/tmp/country-service-kubeconfig
```

The PVC uses the default StorageClass; kind supplies local storage. A different
cluster needs a working default provisioner (or an explicit `storageClassName`).
Kind storage survives pod replacement, but not deletion of the kind cluster.

Build and load the app into kind's container runtime. Also load the existing MySQL
image, avoiding another download inside the cluster:

```sh
docker build -t country-service:local .
docker pull mysql:8.4
kind load docker-image country-service:local mysql:8.4 --name country-service
kubectl --context kind-country-service apply -f k8s/namespace.yaml
kubectl --context kind-country-service apply -f k8s/configmap.yaml
sh k8s/create-secret.sh
kubectl --context kind-country-service apply -f k8s/mysql.yaml
kubectl --context kind-country-service -n country-service rollout status deployment/mysql --timeout=180s
kubectl --context kind-country-service apply -f k8s/app.yaml
kubectl --context kind-country-service -n country-service rollout status deployment/country-service --timeout=180s
```

`create-secret.sh` reads the trusted local `.env` and uses permission-restricted
temporary files. It creates the Secret without printing credentials. It fails if
the Secret already exists; skip it on subsequent deployments. Secret values are
base64 encoded in the API, not inherently encrypted. Restrict access; don't commit
Secret manifests containing real credentials. Updating initialization credentials
does not change existing MySQL users' passwords.

MySQL runs as one Deployment with `Recreate` to avoid overlapping pods mounting
its data volume. A PVC holds `/var/lib/mysql`. This is persistence, not database
high availability. Kubernetes has no Compose-style startup dependency; wait for
MySQL before deploying the app. The application may restart if its database is
unavailable during initialization.

Both app replicas use the same MySQL Service and database. The app is stateless;
uniqueness constraints protect concurrent creation. Resource requests reserve
scheduling capacity, while limits bound CPU/memory. These small local values are
starting points, not production sizing. Connection pools are limited to five
connections per replica to keep database use modest.

## Verify and access

```sh
kubectl --context kind-country-service -n country-service get deployments,pods,services,pvc
kubectl --context kind-country-service -n country-service get endpointslices -l kubernetes.io/service-name=country-service -o yaml
kubectl --context kind-country-service -n country-service port-forward service/country-service 8082:8080
```

Expect `country-service` to show `2/2` ready replicas, MySQL `1/1`, a Bound PVC,
and two ready addresses in the app EndpointSlice. Port 8082 avoids conflicting
with Compose's 8080. In another terminal:

```sh
curl -i http://localhost:8082/actuator/health/liveness
curl -i http://localhost:8082/actuator/health/readiness
curl -i http://localhost:8082/countries
curl -i http://localhost:8082/countries -H 'Content-Type: application/json' -d '{"name":"Tanzania"}'
```

Use the returned ID for GET, PUT, and DELETE using the main README examples,
substituting port 8082. POST should return 201 (409 on repeat), PUT 200, DELETE 204,
and missing records 404. Save a record before the restart checks below.

The ClusterIP Service distributes connections to ready pods. Port-forward chooses
one pod for a session; it does not demonstrate load distribution. To verify each
replica individually, list pod names and port-forward each `pod/<name>` in turn.

Probe behavior:

- Startup allows up to roughly three minutes for initialization before restarting.
- Liveness uses only `livenessState`. MySQL/SOAP outages do not cause liveness restarts.
- Readiness includes `readinessState,db`. Database failures remove pods from Service
  endpoints; SOAP outages do not, preserving stored-data CRUD.
- Probes use the same HTTP port as the API. A failing readiness probe alone does
  not restart a pod. Image/boot failures are separate from readiness failures.

## Verify persistence and scaling

In a terminal with the isolated `KUBECONFIG` exported:

```sh
kubectl --context kind-country-service -n country-service rollout restart deployment/country-service
kubectl --context kind-country-service -n country-service rollout status deployment/country-service --timeout=180s
# Reconnect port-forward if its selected pod was replaced; GET your saved ID.
kubectl --context kind-country-service -n country-service rollout restart deployment/mysql
kubectl --context kind-country-service -n country-service rollout status deployment/mysql --timeout=180s
kubectl --context kind-country-service -n country-service wait --for=condition=Ready pod -l app=country-service --timeout=180s
# GET the same saved ID again after MySQL reconnects.
```

MySQL replacement causes a brief database outage. The same PVC must remain Bound
and the saved record must survive. Delete only your test country after verification.
Shared language catalog rows remain by design.

Two replicas demonstrate horizontal application scaling without adding a second
microservice. The single database and external SOAP service remain bottlenecks.
Circuit breakers and in-memory metrics belong to each replica and reset when it
restarts; they are not shared state. Optional manual scaling:

```sh
kubectl --context kind-country-service -n country-service scale deployment/country-service --replicas=3
kubectl --context kind-country-service -n country-service scale deployment/country-service --replicas=2
```

For code changes, rebuild and load the image, then restart the app Deployment.
Because the local tag uses `IfNotPresent`, restarting without loading the rebuilt
image can run old code. For ConfigMap changes, apply it and restart the app: env
variables are captured when containers start.

## Troubleshoot

```sh
kubectl --context kind-country-service -n country-service get pods,pvc
kubectl --context kind-country-service -n country-service get events --sort-by=.metadata.creationTimestamp
kubectl --context kind-country-service -n country-service describe pod <pod-name>
kubectl --context kind-country-service -n country-service logs <pod-name> --tail=100
kubectl --context kind-country-service -n country-service logs <pod-name> --previous --tail=100
kubectl --context kind-country-service -n country-service logs -l app=country-service --prefix --tail=50
kubectl --context kind-country-service -n country-service describe pvc mysql-data
```

| Symptom | What to inspect |
| --- | --- |
| Docker build reports an internal database input/output error | Check host free space (`df -h .`) and Docker Desktop disk allocation. Free space safely, then restart Docker Desktop when running workloads can be interrupted. Do not delete database volumes or reset Docker as a shortcut. |
| ImagePullBackOff / ErrImagePull | Correct image tag, kind image load, image architecture; don't try to pull the local tag from Docker Hub. |
| Pending pod | Events, CPU/memory requests, node capacity; increase Docker resources if needed. |
| Pending PVC | Default StorageClass and provisioner; never delete a populated PVC as a troubleshooting shortcut. |
| CrashLoopBackOff | Previous logs, Secret key names, DB hostname, OOMKilled status; wrong stored passwords require explicit database changes. |
| App running but not ready | Probe failures, MySQL readiness, Service selectors/endpoints, database connectivity. |
| Port-forward exits | Its selected pod was replaced; wait for rollout and reconnect. |
| POST fails but GET works | Upstream DNS/network, SOAP timeouts, per-pod circuit state and request-correlated logs. |

Use the `X-Request-ID` response header to find the request in JSON logs from both
replicas. With port-forward running, inspect `/actuator/metrics`,
`/actuator/metrics/country.soap.operation`, and
`/actuator/metrics/country.soap.circuit.state`. These show the selected pod's metrics,
not a cluster aggregate. Counters appear only after their first event. Actuator is
unauthenticated here and reachable inside the cluster; keep access restricted.

## Cleanup and production trade-offs

Remove workloads and Services while retaining the Secret, ConfigMap, and PVC:

```sh
kubectl --context kind-country-service -n country-service delete deployment country-service mysql
kubectl --context kind-country-service -n country-service delete service country-service mysql
```

Reapply `mysql.yaml` then `app.yaml` in deployment order to reuse the data.
Deleting the PVC or namespace can delete the backing storage (depending on reclaim
policy). Deleting the kind cluster removes its local data too. Only for intentional
full teardown:

```sh
kind delete cluster --name country-service
```

Minimal assessment choices and production improvements:

- One local MySQL instance: use a managed/HA database, backups, and tested restores
  in production. A PVC is not a backup or a replication strategy.
- `ddl-auto=update`: use controlled schema migrations before rolling out replicas;
  concurrent schema updates are not a production migration plan.
- Local image tag: use a registry, immutable digests, vulnerability scanning, and CI/CD.
- Single-node kind: replicas tolerate app pod loss, not node loss. Add multi-node
  placement, disruption budgets, measured resource sizing and autoscaling as needed.
- Plain ClusterIP/port-forward: add authenticated TLS access, network policies,
  restricted Actuator exposure, and secret encryption/external secret management.
- Local logs/metrics: collect and aggregate them with alerting in production. Consider
  caching or queues only after identifying a concrete bottleneck; neither is needed here.

References: [kind quick start](https://kind.sigs.k8s.io/docs/user/quick-start/) and
[Spring Boot probe guidance](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes).
