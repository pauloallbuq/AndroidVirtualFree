#!/usr/bin/env bash
# Shell dos passos do CI: executa o script do passo e guarda a saída em ci-logs/steps.log
mkdir -p ci-logs
set -o pipefail
bash -eo pipefail "$1" 2>&1 | tee -a ci-logs/steps.log
