#!/usr/bin/env bash

sudo cp /root/.bashrc /home/sereno/.bashrc
sudo cp /root/.vimrc /home/sereno/.vimrc
sudo cp /root/.tmux.conf /home/sereno/.tmux.conf

source /home/sereno/.bashrc
sudo chown sereno:users /home/sereno

set -e;
source ~/.bashrc
cd ~;

echo "[start-tmux.sh] Installing node dependencies"
pushd ~/sereno/frontend/
yarn install
popd

tmux -2 new-session -d -s sereno

tmux new-window -t sereno:1 -n 'shadow watch'
tmux select-window -t sereno:1
tmux send-keys -t sereno 'cd ~/sereno/frontend' enter
tmux send-keys -t sereno 'npx shadow-cljs watch main' enter

tmux new-window -t sereno:2 -n 'backend'
tmux select-window -t sereno:2
tmux send-keys -t sereno 'cd ~/sereno/backend' enter
tmux send-keys -t sereno './scripts/repl' enter

tmux rename-window -t sereno:0 'gulp'
tmux select-window -t sereno:0
tmux send-keys -t sereno 'cd ~/sereno/frontend' enter
tmux send-keys -t sereno 'npx gulp watch' enter

tmux -2 attach-session -t sereno
