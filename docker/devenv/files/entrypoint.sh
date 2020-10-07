#!/usr/bin/env bash

set -e

sudo cp /root/.bashrc /home/sereno/.bashrc
sudo cp /root/.vimrc /home/sereno/.vimrc
sudo cp /root/.tmux.conf /home/sereno/.tmux.conf

sudo chown sereno:users /home/sereno
source /home/sereno/.bashrc

exec "$@"
