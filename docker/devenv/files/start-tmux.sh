#!/usr/bin/env bash

set -e;
source ~/.bashrc

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/sereno/frontend/
yarn install
popd

tmux -2 new-session -d -s sereno

tmux new-window -t sereno:1 -n 'shadow watch'
tmux select-window -t sereno:1
tmux send-keys -t sereno 'cd sereno/frontend' enter C-l
tmux send-keys -t sereno 'npx shadow-cljs watch main' enter

tmux new-window -t sereno:2 -n 'backend'
tmux select-window -t sereno:2
tmux send-keys -t sereno 'cd sereno/backend' enter C-l
tmux send-keys -t sereno './scripts/repl' enter

tmux rename-window -t sereno:0 'gulp'
tmux select-window -t sereno:0
tmux send-keys -t sereno 'cd sereno/frontend' enter C-l
tmux send-keys -t sereno 'npx gulp watch' enter

tmux -2 attach-session -t sereno
