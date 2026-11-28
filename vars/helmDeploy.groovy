def call(Map cfg = [:], Closure body) {
  if (!cfg.namespace) { error 'helmDeploy: namespace is required' }

  def ns        = cfg.namespace
  def agentNs   = cfg.agentNamespace ?: 'jenkins'
  def sa        = cfg.serviceAccount ?: "helm-deployer-${ns}"
  //def helmImage = cfg.helmImage ?: 'alpine/helm:3.16.3'
  def kubectlImg= cfg.kubectlImage ?: 'alpine/k8s:1.34.13'

  // ---- Stage 1: auth + namespace bootstrap (idempotent) ----
  stage("Prepare ${ns}") {
    podTemplate(namespace: agentNs, serviceAccount: 'jenkins-bootstrap', yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: kubectl
    image: ${kubectlImg}
    command: ["sleep"]
    args: ["infinity"]
""") {
      node(POD_LABEL) {
        container('kubectl') {
          def rbac = libraryResource('helm-deployer-rbac.yaml')
            .replace('__TARGET_NS__', ns)
            .replace('__SA__', sa)
            .replace('__AGENT_NS__', agentNs)
          writeFile file: 'rbac.yaml', text: rbac
          withEnv(["NS=${ns}", "SA=${sa}", "AGENT_NS=${agentNs}"]) {
            sh '''#!/bin/bash
set -euo pipefail
if kubectl get namespace "$NS" >/dev/null 2>&1; then
  echo "Namespace $NS already exists"
else
  kubectl create namespace "$NS"
fi
kubectl create serviceaccount "$SA" -n "$AGENT_NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -f rbac.yaml
'''
          }
        }
      }
    }
  }

  // ---- Stage 2: engineer's deployment steps ----
  stage("Deploy to ${ns}") {
    podTemplate(namespace: agentNs, serviceAccount: sa, yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: helm
    image: ${kubectlImg}
    command: ["sleep"]
    args: ["infinity"]
""") {
      node(POD_LABEL) {
        container('helm') {
          checkout scm   // so engineers can use values files from their repo
          withEnv(["NAMESPACE=${ns}", "HELM_NAMESPACE=${ns}"]) {
            body()
          }
        }
      }
    }
  }
}